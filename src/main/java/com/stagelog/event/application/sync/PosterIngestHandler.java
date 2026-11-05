package com.stagelog.event.application.sync;

import com.stagelog.common.exception.ApplicationException;
import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.PosterKeys;
import com.stagelog.event.application.port.KopisPort;
import com.stagelog.event.application.port.KopisPort.Poster;
import com.stagelog.event.application.port.PosterStoragePort;
import com.stagelog.event.domain.Provider;
import com.stagelog.media.application.ImageValidator;
import com.stagelog.media.application.MediaAssets;
import com.stagelog.media.application.MediaOwnerType;
import com.stagelog.ops.application.PermanentMessageFailure;
import com.stagelog.ops.application.QueueHandler;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * jan-poster-ingest Consumer. KOPIS 포스터를 내려받아 검증(ImageValidator)하고 JAN-02 버전 key(내용 hash)로 S3 에 저장한 뒤,
 * KOPIS 스냅샷의 posterUrl 을 그 key 로 바꿔 다시 접는다. 같은 포스터면 key 가 같아 PUT 도(HEAD 200) 접기도 없다(중복 수신 안전).
 * 404·410·빈 본문·검증 실패는 영구 실패로 재시도하지 않는다. 그동안 행사 포스터는 수집이 넣은 값(제공자 원본 URL 또는 이전 key)을 그대로 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PosterIngestHandler implements QueueHandler {

    public static final String QUEUE = "jan-poster-ingest";
    // 포스터 원본 상한. 디코딩 전에 헤더 크기로 거른다 (decompression bomb)
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final int MAX_PX = 6000;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final KopisPort kopis;
    private final PosterStoragePort posterStorage;
    private final ApplySourcesUseCase applySources;
    private final MediaAssets mediaAssets;
    private final TransactionTemplate transactions;

    @Value("${jan.pipeline.async-apply.enabled:false}")
    private boolean enabled;

    /** 발행 본문(Outbox 가 messageId·createdAt 을 더한다). sourceUrl 은 제공자 포스터 URL. */
    public record Payload(Long eventId, String sourceUrl) {
    }

    record Message(String messageId, Long eventId, String sourceUrl, String createdAt) {
    }

    @Override
    public String queue() {
        return QUEUE;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void handle(String body) {
        Message message;
        try {
            message = MAPPER.readValue(body, Message.class);
        } catch (JacksonException e) {
            throw new PermanentMessageFailure("poster 메시지 형식 오류", e);
        }
        // 외부 호출: 포스터 다운로드. 5xx·연결 오류는 예외로 올라가 일시 실패가 된다
        Optional<Poster> poster = kopis.downloadPoster(message.sourceUrl());
        if (poster.isEmpty()) {
            throw new PermanentMessageFailure("포스터 없음(404·410 또는 빈 본문). url=" + message.sourceUrl());
        }
        ImageValidator.Format format;
        try {
            format = ImageValidator.inspect(poster.get().bytes(), MAX_BYTES, MAX_PX).format();
        } catch (ApplicationException e) {
            throw new PermanentMessageFailure("포스터 이미지 검증 실패: " + e.getMessage() + " url=" + message.sourceUrl(), e);
        }
        // 외부 호출: S3 HEAD(같은 key 면 PUT 생략)/PUT
        String key = posterStorage.putVersioned(PosterKeys.versionedPrefix(message.eventId()), format.extension(),
                poster.get().bytes(), format.contentType());
        // ponytail: 같은 행사의 포스터 메시지가 역순으로 오면(URL 이 하루 안에 두 번 바뀜) 옛 포스터가 마지막에 남을 수 있다. 다음 KOPIS 실행이 다시 맞춘다
        boolean changed;
        try {
            // 접기와 변환 asset 등록을 한 트랜잭션으로 묶는다 (롤백되면 둘 다 없다)
            changed = Boolean.TRUE.equals(transactions.execute(status -> {
                boolean adopted = applySources.adoptSourcePoster(message.eventId(), Provider.KOPIS, key);
                mediaAssets.register(MediaOwnerType.EVENT_POSTER, message.eventId(), key, poster.get().bytes());
                return adopted;
            }));
        } catch (ApplicationException e) {
            if (e.getErrorCode().getHttpStatus().is4xxClientError()) {
                throw new PermanentMessageFailure(e.getErrorCode() + ": " + e.getMessage() + " eventId=" + message.eventId(), e);
            }
            throw e;
        }
        log.info("[포스터 입수] messageId={}, eventId={}, key={}, changed={}", message.messageId(), message.eventId(), key, changed);
    }
}
