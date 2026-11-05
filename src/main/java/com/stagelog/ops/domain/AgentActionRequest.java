package com.stagelog.ops.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * MCP 클라이언트가 만든 조치 요청. 요청 자체는 아무것도 바꾸지 않고, 관리자가 승인해야 기존 실행 경로가 돈다.
 * 같은 (type, target_ref) 의 PENDING 은 생성 컬럼 pending_key 의 UNIQUE 가 1건으로 막는다 (엔티티는 그 컬럼을 매핑하지 않는다).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "agent_action_requests")
public class AgentActionRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AgentActionType type;

    @Column(name = "target_ref", nullable = false, length = 100)
    private String targetRef;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "evidence_json", nullable = false, columnDefinition = "json")
    private String evidenceJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentRequestStatus status;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at", columnDefinition = "DATETIME(6)")
    private Instant decidedAt;

    public AgentActionRequest(AgentActionType type, String targetRef, String payloadJson, String reason, String evidenceJson,
                              String requestedBy) {
        this.type = type;
        this.targetRef = targetRef;
        this.payloadJson = payloadJson;
        this.reason = reason;
        this.evidenceJson = evidenceJson;
        this.requestedBy = requestedBy;
        this.status = AgentRequestStatus.PENDING;
    }

    public boolean isPending() {
        return status == AgentRequestStatus.PENDING;
    }

    public void approve(String decidedBy, Instant at) {
        decide(AgentRequestStatus.APPROVED, decidedBy, at);
    }

    public void reject(String decidedBy, Instant at) {
        decide(AgentRequestStatus.REJECTED, decidedBy, at);
    }

    // 승인 뒤 실행 결과. APPROVED 에서만 넘어간다
    public void finishExecution(boolean succeeded) {
        if (status != AgentRequestStatus.APPROVED) {
            throw new IllegalStateException("승인된 요청만 실행 결과를 기록합니다. status=" + status);
        }
        this.status = succeeded ? AgentRequestStatus.EXECUTED : AgentRequestStatus.FAILED;
    }

    private void decide(AgentRequestStatus next, String decidedBy, Instant at) {
        if (!isPending()) {
            throw new IllegalStateException("PENDING 요청만 승인·거절합니다. status=" + status);
        }
        this.status = next;
        this.decidedBy = decidedBy;
        this.decidedAt = at;
    }
}
