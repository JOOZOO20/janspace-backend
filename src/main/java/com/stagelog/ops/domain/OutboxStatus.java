package com.stagelog.ops.domain;

/**
 * Outbox 행 상태. PENDING 은 Relay 가 발행할 대상, PUBLISHED 는 SQS 발행 성공 응답을 받은 것, FAILED 는 SQS 가 본문을 거부해 다시 보내도 안 되는 것.
 */
public enum OutboxStatus {
    PENDING, PUBLISHED, FAILED
}
