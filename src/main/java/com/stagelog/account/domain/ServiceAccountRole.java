package com.stagelog.account.domain;

/**
 * 서비스 계정 권한. Spring Security authority 이름으로 그대로 쓴다. ADMIN 은 사용자 전용이라 여기에 없다.
 * MCP_READER 는 MCP 진단 Tool 이 쓰는 관리자 조회 API 만, MCP_REQUESTER 는 조치 요청 생성만 허용된다 (SecurityConfig).
 */
public enum ServiceAccountRole {
    MCP_READER, MCP_REQUESTER
}
