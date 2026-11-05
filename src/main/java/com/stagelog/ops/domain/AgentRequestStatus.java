package com.stagelog.ops.domain;

/**
 * 조치 요청 상태. PENDING -> REJECTED, 또는 PENDING -> APPROVED -> EXECUTED / FAILED.
 */
public enum AgentRequestStatus {
    PENDING, APPROVED, REJECTED, EXECUTED, FAILED
}
