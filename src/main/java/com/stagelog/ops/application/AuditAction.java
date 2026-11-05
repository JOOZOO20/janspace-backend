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
    PLAYLIST_SYNC,
    // MCP 조치 요청(agent_action_requests)의 PENDING 승인·거절. 승인 뒤 실행된 조치는 그 조치의 action 으로 따로 남는다
    AGENT_REQUEST_APPROVE,
    AGENT_REQUEST_REJECT,
    // DLQ 메시지를 원래 Queue 로 재투입 (관리자 API 와 MCP 조치 승인 경로)
    DLQ_REDRIVE,
    // 미디어 백필 시작·중단 (JAN-05)
    MEDIA_BACKFILL_START,
    MEDIA_BACKFILL_STOP,
    // FAILED rendition 을 PENDING 으로 돌리고 변환 메시지를 다시 발행 (MCP 조치 승인 경로)
    MEDIA_RENDITION_RETRY
}
