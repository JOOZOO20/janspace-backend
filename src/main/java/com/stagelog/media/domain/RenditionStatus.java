package com.stagelog.media.domain;

/**
 * rendition 상태. PROCESSING 은 Lease(lease_owner, lease_expires_at)를 가진 Worker 가 처리 중이라는 뜻이고, Lease 가 만료되면 다른 Worker 가 다시 claim 할 수 있다.
 */
public enum RenditionStatus {
    PENDING, PROCESSING, READY, FAILED
}
