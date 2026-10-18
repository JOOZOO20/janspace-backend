package com.janspace.event.domain;

import com.janspace.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 민감 필드 변경 확인 큐 한 건. (event, field) 당 PENDING 최대 1건이며 새 값이 오면 proposed_value 를 갱신한다.
 * REJECTED 로 남아 있는 동안 같은 proposed_value 는 다음 접기에서 무시된다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "event_source_changes")
public class EventSourceChange extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceChangeField field;

    @Column(name = "current_value")
    private String currentValue;

    @Column(name = "proposed_value", nullable = false)
    private String proposedValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider;

    @Column(name = "detected_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SourceChangeStatus status = SourceChangeStatus.PENDING;

    @Column(name = "resolved_at", columnDefinition = "DATETIME(6)")
    private Instant resolvedAt;

    public EventSourceChange(Event event, SourceChangeField field, String currentValue, String proposedValue,
                             Provider provider, Instant detectedAt) {
        this.event = event;
        this.field = field;
        this.currentValue = currentValue;
        this.proposedValue = proposedValue;
        this.provider = provider;
        this.detectedAt = detectedAt;
    }

    public boolean isPending() {
        return status == SourceChangeStatus.PENDING;
    }

    // 같은 (event, field) 에 다른 값이 또 오면 기존 PENDING 행을 갱신한다. 값이 바뀌었으면 true
    public boolean propose(String currentValue, String proposedValue, Provider provider, Instant detectedAt) {
        if (proposedValue.equals(this.proposedValue)) {
            return false;
        }
        this.currentValue = currentValue;
        this.proposedValue = proposedValue;
        this.provider = provider;
        this.detectedAt = detectedAt;
        return true;
    }

    public void approve(Instant at) {
        status = SourceChangeStatus.APPLIED;
        resolvedAt = at;
    }

    public void reject(Instant at) {
        status = SourceChangeStatus.REJECTED;
        resolvedAt = at;
    }
}
