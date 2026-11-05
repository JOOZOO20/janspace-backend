package com.stagelog.ops.application;

/**
 * 감사 로그 대상 조치. 새 조치를 더하면 AdminAuditIntegrationTest 의 전수 테스트에도 그 조치를 실행하는 경우를 더해야 통과한다.
 */
public enum AuditAction {
    // 출처 변경 확인 큐(event_source_changes)의 PENDING 승인·거절
    PROPOSAL_APPROVE,
    PROPOSAL_REJECT,
    // 행사·공연장 필드 잠금 상태가 실제로 바뀐 경우만
    FIELD_LOCK_SET,
    FIELD_LOCK_UNSET,
    UNMATCHED_SONG_LINK,
    PUSH_DELIVERY_RETRY,
    PLAYLIST_SYNC
}
