package com.stagelog.ops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * 관리자 조치 감사 로그 한 건. append-only 라 변경 메서드가 없고 @Immutable 로 Hibernate 도 UPDATE 를 내지 않는다.
 * 수정 시각이 없어야 하므로 BaseEntity 를 상속하지 않고 created_at 만 직접 둔다 (entity.md 예외, 적용 범위는 이 엔티티).
 */
@Entity
@Getter
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "admin_audit_logs")
public class AdminAuditLog {

    static final int REASON_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;

    @Column(name = "actor_id", nullable = false, length = 100)
    private String actorId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 100)
    private String targetId;

    @Column(name = "before_json", columnDefinition = "json")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "json")
    private String afterJson;

    @Column(length = REASON_MAX_LENGTH)
    private String reason;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant createdAt;

    private AdminAuditLog(String actorType, String actorId, String action, String targetType, String targetId,
                          String beforeJson, String afterJson, String reason, String requestId, Instant createdAt) {
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        // 사유가 길어도 조치를 실패시키지 않도록 잘라서 남긴다
        this.reason = reason != null && reason.length() > REASON_MAX_LENGTH ? reason.substring(0, REASON_MAX_LENGTH) : reason;
        this.requestId = requestId;
        this.createdAt = createdAt;
    }

    public static AdminAuditLog of(String actorType, String actorId, String action, String targetType, String targetId,
                                   String beforeJson, String afterJson, String reason, String requestId, Instant createdAt) {
        return new AdminAuditLog(actorType, actorId, action, targetType, targetId, beforeJson, afterJson, reason, requestId,
                createdAt);
    }
}
