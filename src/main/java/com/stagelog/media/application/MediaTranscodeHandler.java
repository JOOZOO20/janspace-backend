package com.stagelog.media.application;

import com.stagelog.media.application.port.MediaStoragePort;
import com.stagelog.media.domain.MediaAsset;
import com.stagelog.media.domain.MediaAssetRepository;
import com.stagelog.media.domain.MediaRendition;
import com.stagelog.media.domain.MediaRenditionRepository;
import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.ops.application.PermanentMessageFailure;
import com.stagelog.ops.application.QueueHandler;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * rendition 변환 Worker (jan-media-transcode, 백필 jan-media-backfill). 메시지 1건 = rendition 1개.
 * 1) 조건부 UPDATE 로 claim (영향 행 0 이면 다른 Worker 가 처리 중이거나 끝났으므로 메시지만 삭제) 2) 원본 읽기 3) MediaTranscoder 변환·출력 검증
 * 4) S3 업로드 5) 자기 Lease 일 때만 READY + asset 집계. 처리 중 30초마다 Lease 를 연장한다(SQS Visibility 는 ops QueueConsumer 가 같은 주기로 연장).
 * 입구 검증 실패(INVALID_SOURCE)는 재시도 없이 FAILED 후 메시지 삭제. 그 밖의 실패는 PENDING 으로 돌려 SQS backoff 뒤 다시 claim 한다.
 * 처리 중 프로세스가 죽으면 행은 PROCESSING 으로 남고, Lease 가 만료되면 SQS 재전달이나 Zombie 회수(RenditionRecovery)가 다시 처리한다.
 */
@Slf4j
@Service
public class MediaTranscodeHandler implements QueueHandler {

    public static final String TRANSCODE_QUEUE = "jan-media-transcode";
    public static final String BACKFILL_QUEUE = "jan-media-backfill";
    static final long LEASE_SECONDS = 90;
    static final long EXTEND_EVERY_SECONDS = 30;
    // claim 횟수 상한. 이만큼 시도하고도 실패하면 더 시도하지 않고 FAILED + 운영자 알림
    static final int MAX_ATTEMPTS = 5;
    static final int ERROR_MAX_LENGTH = 1000;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    // Worker(프로세스) 식별자. 호스트 이름 대신 무작위 값이라 로그·DB 에 서버 정보가 남지 않는다
    private final String owner = "worker-" + UUID.randomUUID();
    private final MediaRenditionRepository renditions;
    private final MediaAssetRepository assets;
    private final MediaTranscoder transcoder;
    private final MediaStoragePort storage;
    private final OperatorAlert alert;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final boolean split;
    private final int ratio;
    private final int concurrency;
    private final ScheduledExecutorService heartbeats;

    public MediaTranscodeHandler(MediaRenditionRepository renditions, MediaAssetRepository assets, MediaTranscoder transcoder,
                                 MediaStoragePort storage, OperatorAlert alert, TransactionTemplate transactions,
                                 @Value("${jan.media.transcode.enabled:false}") boolean enabled,
                                 @Value("${jan.media.queue.split:true}") boolean split,
                                 @Value("${jan.media.queue.priority-ratio:4}") int ratio,
                                 @Value("${jan.media.worker.concurrency:2}") int concurrency) {
        this.renditions = renditions;
        this.assets = assets;
        this.transcoder = transcoder;
        this.storage = storage;
        this.alert = alert;
        this.transactions = transactions;
        this.enabled = enabled;
        this.split = split;
        this.ratio = ratio;
        this.concurrency = concurrency;
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "media-lease");
            t.setDaemon(true);
            return t;
        });
    }

    /** 발행 본문(Outbox 가 messageId·createdAt 을 더한다). */
    public record Payload(Long renditionId) {
    }

    record Message(String messageId, Long renditionId, String createdAt) {
    }

    @Override
    public String queue() {
        return TRANSCODE_QUEUE;
    }

    // 분리를 끄면(측정 비교용) 백필도 신규 Queue 로 발행하므로 받을 낮은 우선순위 Queue 가 없다
    @Override
    public String lowPriorityQueue() {
        return split ? BACKFILL_QUEUE : null;
    }

    @Override
    public int priorityRatio() {
        return ratio;
    }

    @Override
    public int threads() {
        return concurrency;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    // 백필 발행 대상 Queue
    public String backfillQueue() {
        return split ? BACKFILL_QUEUE : TRANSCODE_QUEUE;
    }

    @Override
    public void handle(String body) {
        Message message;
        try {
            message = MAPPER.readValue(body, Message.class);
        } catch (JacksonException e) {
            throw new PermanentMessageFailure("변환 메시지 형식 오류", e);
        }
        Long id = message.renditionId();

        // 1. claim
        Integer claimed = transactions.execute(s -> renditions.claim(id, owner, LEASE_SECONDS));
        if (claimed == null || claimed == 0) {
            log.info("[미디어 변환 건너뜀] 다른 Worker 가 처리 중이거나 끝났습니다. renditionId={}, messageId={}", id, message.messageId());
            return;
        }
        MediaRendition rendition = renditions.findById(id).orElseThrow();
        MediaAsset asset = assets.findById(rendition.getAssetId()).orElseThrow();
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> extend(id), EXTEND_EVERY_SECONDS, EXTEND_EVERY_SECONDS,
                TimeUnit.SECONDS);
        try {
            // 2~3. 원본 → 변환 (외부 호출: S3 GET, FFmpeg 프로세스)
            byte[] original = storage.get(asset.getOriginalKey())
                    .orElseThrow(() -> new TranscodeException(TranscodeException.Reason.INVALID_SOURCE, "원본이 없습니다 key=" + asset.getOriginalKey()));
            TranscodedRendition out = transcoder.transcode(asset.getId(), original, rendition.profile(), rendition.format());

            // 4. 외부 호출: S3 PUT. key 에 출력 hash 가 있어 두 Worker 가 같은 출력을 올려도 같은 객체다
            storage.putImmutable(out.key(), out.bytes(), out.format().contentType());

            // 5. 자기 Lease 일 때만 READY. 그사이 Lease 를 잃었으면 새 소유자의 결과가 남는다
            Boolean completed = transactions.execute(s -> {
                assets.lockById(asset.getId());
                int n = renditions.complete(id, owner, out.key(), out.width(), out.height(), out.bytes().length);
                if (n == 1) {
                    assets.aggregate(asset.getId());
                }
                return n == 1;
            });
            if (Boolean.TRUE.equals(completed)) {
                log.info("[미디어 변환 완료] assetId={}, renditionId={}, profile={}, format={}, key={}, bytes={}, attempt={}", asset.getId(), id,
                        rendition.getProfile(), rendition.getFormat(), out.key(), out.bytes().length, rendition.getAttempt());
            } else {
                log.warn("[미디어 변환 - Lease 상실] 완료를 반영하지 않습니다. assetId={}, renditionId={}", asset.getId(), id);
            }
        } catch (TranscodeException e) {
            if (!e.retryable()) {
                fail(asset, rendition, e);
                throw new PermanentMessageFailure(e.getMessage(), e);
            }
            retryOrFail(asset, rendition, e);
            throw e;
        } catch (RuntimeException e) {
            retryOrFail(asset, rendition, e);
            throw e;
        } finally {
            // Error(비정상 종료)도 여기서 연장만 멈춘다. 행은 PROCESSING 으로 남아 Lease 만료 뒤 회수된다
            heartbeat.cancel(false);
        }
    }

    // 일시 실패. 시도 횟수가 남았으면 PENDING 으로 돌려 재전달이 claim 하게 하고, 다 썼으면 FAILED + 알림 (메시지는 영구 실패로 삭제)
    private void retryOrFail(MediaAsset asset, MediaRendition rendition, RuntimeException e) {
        if (rendition.getAttempt() >= MAX_ATTEMPTS) {
            fail(asset, rendition, e);
            alert.send("[미디어 변환 실패] 시도 " + rendition.getAttempt() + "회 후 FAILED. assetId=" + asset.getId() + ", renditionId="
                    + rendition.getId() + ", error=" + describe(e));
            throw new PermanentMessageFailure("시도 횟수 초과: " + describe(e), e);
        }
        transactions.executeWithoutResult(s -> renditions.release(rendition.getId(), owner, describe(e)));
    }

    private void fail(MediaAsset asset, MediaRendition rendition, RuntimeException e) {
        transactions.executeWithoutResult(s -> {
            assets.lockById(asset.getId());
            if (renditions.fail(rendition.getId(), owner, describe(e)) == 1) {
                assets.aggregate(asset.getId());
            }
        });
        log.warn("[미디어 변환 실패] assetId={}, renditionId={}, profile={}, format={}, attempt={}, error={}", asset.getId(), rendition.getId(),
                rendition.getProfile(), rendition.getFormat(), rendition.getAttempt(), describe(e));
    }

    private void extend(Long id) {
        try {
            Integer n = transactions.execute(s -> renditions.extendLease(id, owner, LEASE_SECONDS));
            if (n == null || n == 0) {
                log.warn("[미디어 Lease 연장 실패 - 상실] renditionId={}", id);
            }
        } catch (RuntimeException e) {
            // 다음 주기에 다시 시도한다. 그사이 만료되면 다른 Worker 가 claim 하고, 이 Worker 의 완료는 반영되지 않는다
            log.warn("[미디어 Lease 연장 오류] renditionId={}", id, e);
        }
    }

    static String describe(RuntimeException e) {
        String text = e instanceof TranscodeException ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() > ERROR_MAX_LENGTH ? text.substring(0, ERROR_MAX_LENGTH) : text;
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
    }
}
