package com.stagelog.ops.application;

import com.stagelog.ops.application.port.MessageQueuePort;
import com.stagelog.ops.application.port.MessageQueuePort.Received;
import com.stagelog.ops.domain.QueueMessageFailureRepository;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 받은 메시지 한 건 처리 정책. polling thread(ops/infrastructure/QueueWorkers)가 부른다.
 * 1. 처리 중 30초마다 ChangeMessageVisibility 로 Visibility Timeout 을 다시 채운다 (처리가 길어도 다른 Consumer 에 다시 보이지 않게).
 * 2. 성공 -> 삭제. 영구 실패(PermanentMessageFailure) -> 실패 기록 후 삭제.
 * 3. 일시 실패 -> 실패 기록, 삭제하지 않고 수신 횟수 n 에 따라 Visibility 를 30초·2분·10분·30분으로 바꿔 재전달을 늦춘다.
 *    5번째 실패는 0초로 바로 다시 보이게 해, 다음 수신에서 SQS 가 redrive policy(maxReceiveCount 5)로 DLQ 로 옮긴다.
 * 처리 중 프로세스가 죽으면 아무것도 하지 않은 것과 같아 Visibility Timeout 이 지난 뒤 다시 전달된다.
 */
@Slf4j
@Service
public class QueueConsumer {

    static final long EXTEND_EVERY_SECONDS = 30;
    static final int ERROR_MAX_LENGTH = 1000;

    private final MessageQueuePort queue;
    private final QueueMessageFailureRepository failures;
    private final QueueProperties props;
    private final ScheduledExecutorService extensions;

    public QueueConsumer(MessageQueuePort queue, QueueMessageFailureRepository failures, QueueProperties props) {
        this.queue = queue;
        this.failures = failures;
        this.props = props;
        this.extensions = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "queue-visibility");
            t.setDaemon(true);
            return t;
        });
    }

    public void process(QueueHandler handler, Received message) {
        String name = handler.queue();
        String messageId = Outbox.messageIdOf(message.body());
        String failureKey = messageId != null ? messageId : message.sqsMessageId();
        int visibility = props.visibilityOf(name);
        ScheduledFuture<?> extender = extensions.scheduleAtFixedRate(() -> extend(name, message, visibility),
                EXTEND_EVERY_SECONDS, EXTEND_EVERY_SECONDS, TimeUnit.SECONDS);
        RuntimeException failure = null;
        try {
            handler.handle(message.body());
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            // Error(프로세스 비정상) 는 여기서 연장만 멈추고 그대로 올라간다. 메시지는 Visibility Timeout 뒤 다시 전달된다
            extender.cancel(false);
        }

        if (failure == null) {
            queue.delete(name, message.receiptHandle());
            log.info("[Queue 처리 완료] queue={}, messageId={}, receiveCount={}", name, messageId, message.receiveCount());
            return;
        }
        String error = describe(failure);
        if (failure instanceof PermanentMessageFailure) {
            failures.record(failureKey, name, error, true);
            queue.delete(name, message.receiptHandle());
            log.warn("[Queue 영구 실패] 재시도 없이 삭제합니다. queue={}, messageId={}, error={}", name, messageId, error);
            return;
        }
        failures.record(failureKey, name, error, false);
        int delay = message.receiveCount() >= QueueProperties.MAX_RECEIVE_COUNT ? 0 : props.backoffAfter(message.receiveCount());
        queue.changeVisibility(name, message.receiptHandle(), delay);
        log.warn("[Queue 일시 실패] queue={}, messageId={}, receiveCount={}, 재전달대기초={}{}", name, messageId, message.receiveCount(),
                delay, message.receiveCount() >= QueueProperties.MAX_RECEIVE_COUNT ? " (다음 수신에서 DLQ 로 이동)" : "", failure);
    }

    private void extend(String name, Received message, int visibility) {
        try {
            queue.changeVisibility(name, message.receiptHandle(), visibility);
        } catch (RuntimeException e) {
            // 연장 실패는 다음 주기에 다시 시도한다. 그사이 만료되면 다른 Consumer 가 받아 중복 처리된다(멱등이라 결과는 같다)
            log.warn("[Queue Visibility 연장 실패] queue={}, sqsMessageId={}", name, message.sqsMessageId(), e);
        }
    }

    private static String describe(RuntimeException e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() > ERROR_MAX_LENGTH ? text.substring(0, ERROR_MAX_LENGTH) : text;
    }

    @PreDestroy
    void shutdown() {
        extensions.shutdownNow();
    }
}
