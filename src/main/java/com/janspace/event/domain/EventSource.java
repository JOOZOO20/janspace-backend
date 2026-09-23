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
 * 행사에 확정 연결된 출처. 행사당 제공자별 하나. raw 는 정규화된 SourceSnapshot JSON.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "event_sources")
public class EventSource extends BaseEntity {

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

    @Column(name = "fetched_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant fetchedAt;

    public EventSource(Event event, Provider provider, String externalId, String occurrenceKey, String raw, Instant fetchedAt) {
        this.event = event;
        this.provider = provider;
        this.externalId = externalId;
        this.occurrenceKey = occurrenceKey;
        this.raw = raw;
        this.fetchedAt = fetchedAt;
    }

    public void refresh(String externalId, String occurrenceKey, String raw, Instant fetchedAt) {
        this.externalId = externalId;
        this.occurrenceKey = occurrenceKey;
        this.raw = raw;
        this.fetchedAt = fetchedAt;
    }

    // 날짜 이동 승인 시 회차 키만 옮긴다
    public void moveOccurrence(String occurrenceKey) {
        this.occurrenceKey = occurrenceKey;
    }
}
