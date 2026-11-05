package com.stagelog.ops.application;

import com.stagelog.ops.application.port.MessageQueuePort;
import com.stagelog.ops.domain.OutboxEvent;
import com.stagelog.ops.domain.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox Relay. PENDING 을 오래된 것부터 최대 100건 잠가(FOR UPDATE SKIP LOCKED) SQS 로 발행하고, 발행 성공 응답을 받은 행만 PUBLISHED 로 바꾼다.
 * 발행 뒤 커밋 전에 서버가 죽으면 같은 메시지가 다시 발행된다(유실 대신 중복). Consumer 가 중복 수신을 전제로 하는 이유다.
 * 여러 서버가 동시에 돌아도 잠긴 행은 건너뛰므로 같은 행을 같이 발행하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelay {

    static final int BATCH = 100;

    private final OutboxEventRepository repository;
    private final MessageQueuePort queue;

    // 이번에 발행한 건수
    @Transactional
    public int relayOnce() {
        List<OutboxEvent> pending = repository.lockPending(BATCH);
        int published = 0;
        for (OutboxEvent event : pending) {
            try {
                // 외부 호출: SQS SendMessage. 행 잠금을 쥔 채로 부른다
                queue.send(event.getQueueName(), event.getPayload());
                event.markPublished(Instant.now());
                published++;
            } catch (MessageQueuePort.RejectedException e) {
                event.failAttempt(true);
                log.error("[Outbox 발행 거부] Queue 가 본문을 거부해 FAILED 로 둡니다. messageId={}, queue={}", event.getMessageId(),
                        event.getQueueName(), e);
            } catch (RuntimeException e) {
                // Queue 장애로 보고 이번 주기를 멈춘다. 남은 행은 PENDING 그대로 다음 주기에 다시 보낸다
                event.failAttempt(false);
                log.warn("[Outbox 발행 실패] 다음 주기에 다시 발행합니다. messageId={}, queue={}, attempts={}", event.getMessageId(),
                        event.getQueueName(), event.getAttempts(), e);
                break;
            }
        }
        if (!pending.isEmpty()) {
            log.info("[Outbox 발행] claimed={}, published={}", pending.size(), published);
        }
        return published;
    }
}
