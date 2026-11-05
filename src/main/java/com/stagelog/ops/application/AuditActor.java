package com.stagelog.ops.application;

import java.util.Objects;

/**
 * 감사 로그의 조치 주체. ADMIN 은 관리자 사용자 ID, MCP_AGENT 는 MCP 클라이언트 식별자, SYSTEM 은 작업 이름을 id 로 쓴다.
 */
public record AuditActor(Type type, String id) {

    public enum Type {
        ADMIN, MCP_AGENT, SYSTEM
    }

    public AuditActor {
        Objects.requireNonNull(type);
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("감사 로그 주체 ID 가 비었습니다");
        }
    }

    public static AuditActor admin(Long userId) {
        return new AuditActor(Type.ADMIN, String.valueOf(Objects.requireNonNull(userId)));
    }

    public static AuditActor mcpAgent(String clientId) {
        return new AuditActor(Type.MCP_AGENT, clientId);
    }

    public static AuditActor system(String jobName) {
        return new AuditActor(Type.SYSTEM, jobName);
    }
}
