package com.stagelog.event.application.sync;

import com.stagelog.common.exception.ApplicationException;
import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.domain.Provider;
import com.stagelog.ops.application.PermanentMessageFailure;
import com.stagelog.ops.application.QueueHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * jan-source-apply Consumer. 출처 스냅샷이 바뀐 행사를 저장된 모든 출처로 다시 접는다(ApplySourcesUseCase.refold).
 * 메시지는 "이 행사를 다시 접어라"는 신호일 뿐이고 내용은 DB 의 event_sources 에서 읽으므로, 같은 메시지가 여러 번 와도, 옛 스냅샷의
 * 메시지가 늦게 와도 결과는 마지막으로 저장된 출처를 접은 것과 같다. 행사 행 잠금(refold)이 병렬 Consumer 사이의 접기를 직렬화한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceApplyHandler implements QueueHandler {

    public static final String QUEUE = "jan-source-apply";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ApplySourcesUseCase applySources;

    @Value("${jan.pipeline.async-apply.enabled:false}")
    private boolean enabled;

    /** 발행 본문(Outbox 가 messageId·createdAt 을 더한다). eventId 는 연결된 행사, snapshotHash 는 저장한 스냅샷 JSON 의 sha256. */
    public record Payload(String provider, String externalId, String occurrenceKey, String snapshotHash, Long eventId) {
    }

    record Message(String messageId, String provider, String externalId, String occurrenceKey, String snapshotHash, Long eventId,
                   String createdAt) {
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
            throw new PermanentMessageFailure("apply 메시지 형식 오류", e);
        }
        // 수집은 행사가 정해진 출처만 저장하므로(event_sources.event_id NOT NULL) eventId 가 비는 메시지는 만들지 않는다
        if (message.eventId() == null) {
            throw new PermanentMessageFailure("eventId 가 없는 apply 메시지. provider=" + message.provider() + ", externalId="
                    + message.externalId());
        }
        try {
            applySources.refold(message.eventId());
        } catch (ApplicationException e) {
            // 4xx 업무 오류(그사이 삭제된 행사 등)는 다시 받아도 같다. 5xx 는 일시 실패로 다시 받는다
            if (e.getErrorCode().getHttpStatus().is4xxClientError()) {
                throw new PermanentMessageFailure(e.getErrorCode() + ": " + e.getMessage() + " eventId=" + message.eventId(), e);
            }
            throw e;
        }
        log.info("[출처 접기 메시지 처리] messageId={}, eventId={}, provider={}, snapshotHash={}", message.messageId(),
                message.eventId(), Provider.valueOf(message.provider()), message.snapshotHash());
    }
}
