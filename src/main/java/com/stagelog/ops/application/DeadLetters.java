package com.stagelog.ops.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.ops.application.port.MessageQueuePort;
import com.stagelog.ops.application.port.MessageQueuePort.Received;
import com.stagelog.ops.domain.QueueMessageFailure;
import com.stagelog.ops.domain.QueueMessageFailureRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DLQ 조회와 redrive(원래 Queue 로 재투입). 대상 Queue 는 등록된 QueueHandler 의 Queue 만이다.
 * SQS 에는 메시지를 지우지 않고 보는 기능(peek)이 없어 짧은 Visibility 로 받아 본 뒤 그대로 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetters {

    static final int SUMMARY_LENGTH = 200;
    // 목록 조회 중 같은 메시지를 다시 받지 않을 만큼만 숨긴다
    static final int PEEK_VISIBILITY_SECONDS = 5;
    static final int REDRIVE_VISIBILITY_SECONDS = 30;
    static final int RECEIVE_MAX = 10;

    private final MessageQueuePort queue;
    private final QueueMessageFailureRepository failures;
    private final List<QueueHandler> handlers;

    /** DLQ 메시지. attempts 는 원래 Queue 에서의 처리 실패 횟수, lastError 는 마지막 실패 (기록이 없으면 null). */
    public record DeadLetter(String messageId, int attempts, String lastError, Instant lastFailedAt, String bodySummary) {
    }

    /** redrive 결과. notFound 는 DLQ 에서 찾지 못한 messageId (이미 재투입됐거나 없는 ID). */
    public record Redrive(List<String> redriven, List<String> notFound) {
    }

    // ponytail: 받아 본 순서대로 limit 건까지. 메시지가 많으면 SQS 가 고르는 일부만 보인다(전수 조회 아님)
    public List<DeadLetter> list(String queueName, int limit) {
        String dlq = QueueProperties.deadLetterQueue(known(queueName));
        Map<String, Received> seen = new LinkedHashMap<>();
        for (int round = 0; round < limit / RECEIVE_MAX + 3 && seen.size() < limit; round++) {
            List<Received> batch = queue.receive(dlq, Math.min(RECEIVE_MAX, limit - seen.size()), 0, PEEK_VISIBILITY_SECONDS);
            int before = seen.size();
            batch.forEach(m -> seen.putIfAbsent(m.sqsMessageId(), m));
            if (seen.size() == before) {
                break;
            }
        }
        Map<String, String> ids = new LinkedHashMap<>();
        seen.values().forEach(m -> ids.put(m.sqsMessageId(), idOf(m)));
        Map<String, QueueMessageFailure> byId = failures.findAllByMessageIdIn(new HashSet<>(ids.values())).stream()
                .collect(Collectors.toMap(QueueMessageFailure::getMessageId, Function.identity()));
        List<DeadLetter> out = new ArrayList<>();
        for (Received m : seen.values()) {
            QueueMessageFailure failure = byId.get(ids.get(m.sqsMessageId()));
            out.add(new DeadLetter(ids.get(m.sqsMessageId()), failure == null ? m.receiveCount() : failure.getAttempts(),
                    failure == null ? null : failure.getLastError(), failure == null ? null : failure.getLastFailedAt(),
                    summary(m.body())));
        }
        log.info("[DLQ 조회] queue={}, limit={}, count={}", queueName, limit, out.size());
        return out;
    }

    // DLQ 에서 messageId 가 맞는 메시지를 찾아 원래 Queue 로 보내고 DLQ 에서 지운다. 받아 본 다른 메시지는 바로 다시 보이게 돌려 놓는다
    public Redrive redrive(String queueName, List<String> messageIds) {
        String source = known(queueName);
        String dlq = QueueProperties.deadLetterQueue(source);
        Set<String> wanted = new LinkedHashSet<>(messageIds);
        List<String> redriven = new ArrayList<>();
        Set<String> skipped = new HashSet<>();
        while (!wanted.isEmpty()) {
            List<Received> batch = queue.receive(dlq, RECEIVE_MAX, 0, REDRIVE_VISIBILITY_SECONDS);
            boolean progressed = false;
            for (Received m : batch) {
                String id = idOf(m);
                if (wanted.remove(id)) {
                    // 외부 호출: 원래 Queue 로 SendMessage 후 DLQ 에서 DeleteMessage. 보낸 뒤 삭제 전에 실패하면 다음 redrive 가 한 번 더 보낸다(중복은 Consumer 가 견딘다)
                    queue.send(source, m.body());
                    queue.delete(dlq, m.receiptHandle());
                    redriven.add(id);
                    progressed = true;
                } else {
                    progressed |= skipped.add(m.sqsMessageId());
                    queue.changeVisibility(dlq, m.receiptHandle(), 0);
                }
            }
            if (batch.isEmpty() || !progressed) {
                break;
            }
        }
        log.info("[DLQ redrive] queue={}, requested={}, redriven={}, notFound={}", queueName, messageIds.size(), redriven.size(),
                wanted.size());
        return new Redrive(redriven, new ArrayList<>(wanted));
    }

    private String known(String queueName) {
        return handlers.stream().map(QueueHandler::queue).filter(queueName::equals).findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.QUEUE_NOT_FOUND, "존재하지 않는 Queue 입니다: " + queueName));
    }

    private static String idOf(Received m) {
        String id = Outbox.messageIdOf(m.body());
        return id != null ? id : m.sqsMessageId();
    }

    private static String summary(String body) {
        return body == null || body.length() <= SUMMARY_LENGTH ? body : body.substring(0, SUMMARY_LENGTH) + "...";
    }
}
