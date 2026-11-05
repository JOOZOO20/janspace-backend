package com.stagelog.ops.domain;

/**
 * MCP request_action 이 만들 수 있는 조치 유형. 이 4개 밖의 조치는 요청조차 만들 수 없다.
 * 실행은 ops.application.port.AgentActionHandler 구현이 맡고, 구현이 없는 유형은 승인 시 409 AGENT_ACTION_NOT_EXECUTABLE 이다.
 */
public enum AgentActionType {
    RETRY_PLAYLIST_SYNC, REDRIVE_DEAD_LETTER, LINK_UNMATCHED_SONG, RETRY_MEDIA_RENDITION
}
