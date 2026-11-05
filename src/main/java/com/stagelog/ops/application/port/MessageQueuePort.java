package com.stagelog.ops.application.port;

import java.util.List;

/**
 * 메시지 Queue 포트 (SQS). 구현은 ops/infrastructure. queue 는 Queue 이름(jan-source-apply 등)이고 구현이 URL 로 바꾼다.
 */
public interface MessageQueuePort {

    /** 받은 메시지. receiveCount 는 SQS ApproximateReceiveCount (이번 수신 포함). */
    record Received(String sqsMessageId, String receiptHandle, String body, int receiveCount) {
    }

    /** Queue 가 본문을 거부했다. 같은 본문을 다시 보내도 성공하지 않는다. */
    class RejectedException extends RuntimeException {
        public RejectedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // 실패하면 RuntimeException (거부는 RejectedException)
    void send(String queue, String body);

    // waitSeconds 동안 Long Polling. visibilitySeconds 가 null 이면 Queue 기본값
    List<Received> receive(String queue, int max, int waitSeconds, Integer visibilitySeconds);

    void changeVisibility(String queue, String receiptHandle, int seconds);

    void delete(String queue, String receiptHandle);

    // Queue 와 DLQ 를 만들고 redrive policy(maxReceiveCount)를 건다. 이미 있으면 속성만 맞춘다
    void ensureQueue(String queue, String deadLetterQueue, int visibilityTimeoutSeconds, int maxReceiveCount);
}
