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
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 내부 행사가 없는 외부 회차. 관리자가 새 행사로 만들거나(accept, eventId 없음) 기존 행사에 붙인다(accept, eventId 있음).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "event_discoveries")
public class EventDiscovery extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(name = "occurrence_key", nullable = false, length = 20)
    private String occurrenceKey;

    @Column(nullable = false, columnDefinition = "json")
    private String raw;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "venue_name", length = 255)
    private String venueName;

    @Column(length = 255)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiscoveryStatus status = DiscoveryStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;

    @Column(name = "resolved_at", columnDefinition = "DATETIME(6)")
    private Instant resolvedAt;

    public EventDiscovery(Provider provider, String externalId, String occurrenceKey, String raw, LocalDate eventDate,
                          String venueName, String city) {
        this.provider = provider;
        this.externalId = externalId;
        this.occurrenceKey = occurrenceKey;
        this.raw = raw;
        this.eventDate = eventDate;
        this.venueName = venueName;
        this.city = city;
    }

    public boolean isPending() {
        return status == DiscoveryStatus.PENDING;
    }

    public void accept(Event event, Instant at) {
        this.status = DiscoveryStatus.ACCEPTED;
        this.event = event;
        this.resolvedAt = at;
    }

    public void dismiss(Instant at) {
        this.status = DiscoveryStatus.DISMISSED;
        this.resolvedAt = at;
    }

    // 연결됐던 행사가 삭제됨: 행사 연결을 끊고 DISMISSED 로 남겨 다음 동기화가 같은 회차를 다시 올리지 않게 한다
    public void dismissForDeletedEvent(Instant at) {
        this.event = null;
        dismiss(at);
    }
}
