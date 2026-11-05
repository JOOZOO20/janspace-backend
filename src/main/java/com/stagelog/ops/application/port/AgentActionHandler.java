package com.stagelog.ops.application.port;

import java.util.Map;

/**
 * 승인된 MCP 조치 요청을 기존 실행 경로로 처리하는 포트. 조치를 소유한 기능이 구현한다 (ops 가 그 기능을 참조하면 순환이 생긴다).
 * type 은 ops.domain.AgentActionType 의 이름이다. 실패는 예외로 알리며, 요청은 FAILED 로 남고 예외는 승인한 관리자에게 간다.
 */
public interface AgentActionHandler {

    String type();

    void execute(String targetRef, Map<String, Object> payload);
}
