package com.stagelog.ops.application;

import com.stagelog.ops.domain.OutboxEvent;
import com.stagelog.ops.domain.OutboxEventRepository;
import com.stagelog.ops.domain.QueueMessageFailure;
import com.stagelog.ops.domain.QueueMessageFailureRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queue 메시지 실패 기록 조회 공개 계약. 실패 기록(queue_message_failures)에 그 메시지의 Outbox 본문을 붙여 돌려준다.
 * 본문 안의 대상 ID(예: renditionId)로 고르는 일은 호출 기능이 Java 에서 한다. JSON 내용을 조회 조건으로 쓰지 않기 위해서다(entity.md §3(h)).
 */
@Service
@RequiredArgsConstructor
public class QueueFailureQuery {

    private final QueueMessageFailureRepository failures;
    private final OutboxEventRepository outbox;

    /** payload 는 Outbox 본문이며 Outbox 행이 없으면(직접 보낸 메시지) null. */
    public record Failure(String messageId, String queue, int attempts, String lastError, boolean permanent, Instant lastFailedAt,
                          String payload) {
    }

    // ponytail: 그 Queue 들의 실패 기록 전부를 읽는다. 실패 기록이 수만 건이 되면 대상 ID 컬럼을 실패 기록에 둔다
    @Transactional(readOnly = true)
    public List<Failure> byQueues(Collection<String> queues) {
        List<QueueMessageFailure> found = failures.findAllByQueueNameInOrderByLastFailedAtDesc(queues);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<String, String> payloads = outbox.findAllByMessageIdIn(found.stream().map(QueueMessageFailure::getMessageId).toList()).stream()
                .collect(Collectors.toMap(OutboxEvent::getMessageId, OutboxEvent::getPayload, (a, b) -> a));
        return found.stream().map(f -> new Failure(f.getMessageId(), f.getQueueName(), f.getAttempts(), f.getLastError(), f.isPermanent(),
                f.getLastFailedAt(), payloads.get(f.getMessageId()))).toList();
    }
}
