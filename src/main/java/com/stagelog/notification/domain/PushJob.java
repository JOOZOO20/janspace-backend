package com.stagelog.notification.domain;

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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 x 종류 1건의 발송 잡. 예약 단계 전이(재예약·취소)만 엔티티가 하고, 선점·물질화·완료는 여러 인스턴스가
 * 동시에 달려도 안전하도록 PushJobRepository 의 조건부 UPDATE 로만 바꾼다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "push_jobs")
public class PushJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PushKind kind;

    @Column(name = "due_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PushJobStatus status = PushJobStatus.SCHEDULED;

    @Column(name = "claimed_at", columnDefinition = "DATETIME(6)")
    private Instant claimedAt;

    @Column(name = "materialized_at", columnDefinition = "DATETIME(6)")
    private Instant materializedAt;

    private PushJob(Long eventId, PushKind kind, Instant dueAt) {
        this.eventId = Objects.requireNonNull(eventId);
        this.kind = Objects.requireNonNull(kind);
        this.dueAt = Objects.requireNonNull(dueAt);
    }

    public static PushJob schedule(Long eventId, PushKind kind, Instant dueAt) {
        return new PushJob(eventId, kind, dueAt);
    }

    // 발송이 시작된(DISPATCHING/DONE) 잡은 재예약하지 않는다. CANCELLED 였던 잡은 다시 SCHEDULED 로 살린다
    public boolean reschedule(Instant dueAt) {
        if (isDispatchStarted()) {
            return false;
        }
        this.dueAt = Objects.requireNonNull(dueAt);
        this.status = PushJobStatus.SCHEDULED;
        return true;
    }

    public boolean cancel() {
        if (status != PushJobStatus.SCHEDULED) {
            return false;
        }
        status = PushJobStatus.CANCELLED;
        return true;
    }

    // 관리자 수동 재시도로 배달이 다시 열리면 DONE 잡을 DISPATCHING 으로 되돌린다. 물질화는 이미 끝났으므로 배달은 늘지 않는다
    public boolean reopen() {
        if (status != PushJobStatus.DONE) {
            return false;
        }
        status = PushJobStatus.DISPATCHING;
        return true;
    }

    public boolean isDispatchStarted() {
        return status == PushJobStatus.DISPATCHING || status == PushJobStatus.DONE;
    }
}
