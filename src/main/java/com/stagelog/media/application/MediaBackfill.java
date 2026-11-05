package com.stagelog.media.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.media.application.port.MediaStoragePort;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 기존 원본 백필. 소유자 ID 순으로 원본을 읽어 asset·rendition 행과 변환 메시지를 만들고(MediaAssets.register), 초당 rate 건으로 발행한다.
 * 메시지는 jan-media-backfill(분리를 끄면 jan-media-transcode)로 가서 신규 원본보다 낮은 우선순위로 처리된다.
 * 중단 후 다시 시작하면 이미 현재 key 로 등록한 소유자는 원본을 받지 않고 건너뛰므로, 끊긴 곳부터 이어서 발행한다. 진행 위치는 status 의 cursor 다.
 * ponytail: 실행 중 표시는 이 프로세스 메모리에만 있다. 서버 여러 대에서 동시에 부르면 둘 다 돈다(등록은 유일 제약으로 겹치지 않는다). 막아야 하면 job_leases 로 잡는다
 */
@Slf4j
@Service
public class MediaBackfill {

    static final int PAGE = 100;

    private final List<MediaOriginals> sources;
    private final MediaAssets mediaAssets;
    private final MediaStoragePort storage;
    private final MediaTranscodeHandler handler;
    private final boolean enabled;
    private final int ratePerSecond;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "media-backfill");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean stopRequested;
    private volatile Status status = new Status(null, 0, false, 0, 0, 0, 0, null, null);

    public MediaBackfill(List<MediaOriginals> sources, MediaAssets mediaAssets, MediaStoragePort storage, MediaTranscodeHandler handler,
                         @Value("${jan.media.transcode.enabled:false}") boolean enabled,
                         @Value("${jan.media.backfill.rate-per-second:20}") int ratePerSecond) {
        this.sources = sources;
        this.mediaAssets = mediaAssets;
        this.storage = storage;
        this.handler = handler;
        this.enabled = enabled;
        this.ratePerSecond = ratePerSecond;
    }

    /** 진행 상태. cursor 는 마지막으로 본 소유자 ID, registered 는 이번 실행에서 발행한 asset 수, skipped 는 이미 등록돼 건너뛴 수. */
    public record Status(MediaOwnerType ownerType, int limit, boolean running, long cursor, int registered, int skipped, int missing,
                         Instant startedAt, Instant finishedAt) {
    }

    // 백그라운드로 시작하고 바로 돌아온다
    public Status start(MediaOwnerType ownerType, int limit) {
        if (!enabled) {
            throw new ConflictException(ErrorCode.MEDIA_TRANSCODE_DISABLED);
        }
        if (limit <= 0) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "limit 은 1 이상이어야 합니다.");
        }
        MediaOriginals source = sources.stream().filter(s -> s.supports(ownerType)).findFirst()
                .orElseThrow(() -> new BadRequestException(ErrorCode.INVALID_INPUT, "백필할 수 없는 ownerType 입니다: " + ownerType));
        if (!running.compareAndSet(false, true)) {
            throw new ConflictException(ErrorCode.MEDIA_BACKFILL_RUNNING);
        }
        stopRequested = false;
        status = new Status(ownerType, limit, true, 0, 0, 0, 0, Instant.now(), null);
        log.info("[미디어 백필 시작] ownerType={}, limit={}, ratePerSecond={}, queue={}", ownerType, limit, ratePerSecond, handler.backfillQueue());
        runner.submit(() -> run(source, ownerType, limit));
        return status;
    }

    // 다음 원본 전에 멈춘다. 이미 발행한 메시지는 그대로 처리된다
    public Status stop() {
        stopRequested = true;
        return status;
    }

    public Status status() {
        return status;
    }

    private void run(MediaOriginals source, MediaOwnerType ownerType, int limit) {
        long cursor = 0;
        int registered = 0;
        int skipped = 0;
        int missing = 0;
        long started = System.nanoTime();
        try {
            while (registered < limit && !stopRequested) {
                List<MediaOriginals.Entry> page = source.after(ownerType, cursor, PAGE);
                if (page.isEmpty()) {
                    break;
                }
                for (MediaOriginals.Entry entry : page) {
                    if (registered >= limit || stopRequested) {
                        break;
                    }
                    cursor = entry.ownerId();
                    // 1. 이미 현재 key 로 등록됐으면 원본을 받지 않는다 (재개)
                    if (mediaAssets.isRegistered(ownerType, entry.ownerId(), entry.key())) {
                        skipped++;
                        continue;
                    }
                    // 2. 발행 속도 제한: n 번째 등록은 시작 + n/rate 초 이후
                    pace(started, registered);
                    // 3. 외부 호출: S3 GET (sha256·크기를 구하려고 원본을 읽는다)
                    Optional<byte[]> original = storage.get(entry.key());
                    if (original.isEmpty()) {
                        missing++;
                        log.warn("[미디어 백필 - 원본 없음] ownerType={}, ownerId={}, key={}", ownerType, entry.ownerId(), entry.key());
                        continue;
                    }
                    mediaAssets.register(ownerType, entry.ownerId(), entry.key(), original.get(), handler.backfillQueue());
                    registered++;
                    status = new Status(ownerType, limit, true, cursor, registered, skipped, missing, status.startedAt(), null);
                }
            }
        } catch (RuntimeException e) {
            log.error("[미디어 백필 중단] ownerType={}, cursor={}, registered={}", ownerType, cursor, registered, e);
        } finally {
            status = new Status(ownerType, limit, false, cursor, registered, skipped, missing, status.startedAt(), Instant.now());
            running.set(false);
            log.info("[미디어 백필 종료] ownerType={}, cursor={}, registered={}, skipped={}, missing={}, stopped={}", ownerType, cursor,
                    registered, skipped, missing, stopRequested);
        }
    }

    private void pace(long startedNanos, int registered) {
        long dueNanos = startedNanos + TimeUnit.SECONDS.toNanos(registered) / ratePerSecond;
        long waitNanos = dueNanos - System.nanoTime();
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopRequested = true;
            }
        }
    }

    @PreDestroy
    void shutdown() {
        stopRequested = true;
        runner.shutdownNow();
    }
}
