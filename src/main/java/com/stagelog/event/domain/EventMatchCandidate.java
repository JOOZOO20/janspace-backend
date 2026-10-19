package com.stagelog.event.domain;

import com.stagelog.common.entity.BaseEntity;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 기존 행사에 대한 매칭 후보 (후보가 둘 이상일 때). 관리자가 하나를 고르면 출처로 확정되고 나머지는 삭제된다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "event_match_candidates")
public class EventMatchCandidate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(name = "occurrence_key", nullable = false, length = 20)
    private String occurrenceKey;

    @Column(nullable = false, columnDefinition = "json")
    private String raw;

    public EventMatchCandidate(Event event, Provider provider, String externalId, String occurrenceKey, String raw) {
        this.event = event;
        this.provider = provider;
        this.externalId = externalId;
        this.occurrenceKey = occurrenceKey;
        this.raw = raw;
    }
}
