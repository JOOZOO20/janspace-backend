package com.janspace.event.domain;

import com.janspace.common.entity.SoftDeletableBaseEntity;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.locale.SupportedLocale;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 행사 한 회차. 벽시계 시각(starts_at_local/ends_at_local + timezone)이 원본이고 UTC 순간(starts_at/ends_at)은 recomputeInstants 로 파생한다.
 * 불변식: starts_at_local != null 이면 event_date == starts_at_local.toLocalDate(). 관리자 수정(adminUpdate)은 값 변경과 잠금을 한 번에 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "events")
public class Event extends SoftDeletableBaseEntity {

    public static final String DEFAULT_TIMEZONE = "Asia/Seoul";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id")
    private Venue venue;

    @Column(nullable = false, length = 255)
    private String name;

    @Convert(converter = SupportedLocaleConverter.class)
    @Column(name = "original_locale", nullable = false, length = 10)
    private SupportedLocale originalLocale;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "starts_at_local", columnDefinition = "DATETIME(0)")
    private LocalDateTime startsAtLocal;

    @Column(name = "ends_at_local", columnDefinition = "DATETIME(0)")
    private LocalDateTime endsAtLocal;

    @Column(nullable = false, length = 64)
    private String timezone = DEFAULT_TIMEZONE;

    @Column(name = "starts_at", columnDefinition = "DATETIME(6)")
    private Instant startsAt;

    @Column(name = "ends_at", columnDefinition = "DATETIME(6)")
    private Instant endsAt;

    @Column(name = "running_minutes")
    private Integer runningMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType type;

    @Column(length = 100)
    private String season;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status = EventStatus.ACTIVE;

    @Column(name = "poster_key", length = 2048)
    private String posterKey;

    @Column(name = "ticket_url", length = 2048)
    private String ticketUrl;

    @Convert(converter = EventLockedFieldsConverter.class)
    @Column(name = "locked_fields", nullable = false, columnDefinition = "json")
    private Set<EventLockableField> lockedFields = EnumSet.noneOf(EventLockableField.class);

    @Column(name = "last_alerted_on")
    private LocalDate lastAlertedOn;

    private Event(String name, SupportedLocale originalLocale, EventType type, LocalDate eventDate) {
        this.name = Venue.requireText(name);
        this.originalLocale = Objects.requireNonNull(originalLocale);
        this.type = Objects.requireNonNull(type);
        this.eventDate = Objects.requireNonNull(eventDate);
    }

    public static Event create(String name, SupportedLocale originalLocale, EventType type, LocalDate eventDate) {
        return new Event(name, originalLocale, type, eventDate);
    }

    public Set<EventLockableField> getLockedFields() {
        return Collections.unmodifiableSet(lockedFields);
    }

    public boolean isLocked(EventLockableField field) {
        return lockedFields.contains(field);
    }

    public Long venueId() {
        return venue == null ? null : venue.getId();
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    // 관리자 수정: 값 변경 + 잠금. 값이 실제로 바뀌었으면 true
    public boolean adminUpdate(EventLockableField field, Object value) {
        boolean changed = apply(field, value);
        lockedFields.add(field);
        return changed;
    }

    // 잠금 해제 = "출처 값 따르기". 호출자가 곧바로 접기를 다시 실행한다
    public void unlock(EventLockableField field) {
        lockedFields.remove(field);
    }

    // 관리자 값 지우기: 잠긴(관리자 고정) 필드만 비우고 잠금을 푼다. 호출자가 곧바로 접기를 다시 실행해 출처 값이 있으면 채운다.
    // 잠기지 않은 필드는 이미 출처를 따르는 중이라 그대로 두고 false. 비울 수 있는 필드(nullable)에만 쓴다
    public boolean adminClear(EventLockableField field) {
        if (!lockedFields.remove(field)) {
            return false;
        }
        apply(field, null);
        return true;
    }

    // 출처 접기 결과 적용. 잠긴 필드는 무시하고 false
    public boolean applySource(EventLockableField field, Object value) {
        if (isLocked(field)) {
            return false;
        }
        return apply(field, value);
    }

    // 벽시계 시각·시간대에서 UTC 순간을 다시 계산한다. UTC 값이 바뀌었으면 true
    public boolean recomputeInstants() {
        ZoneId zone = zone();
        Instant nextStart = startsAtLocal == null ? null : startsAtLocal.atZone(zone).toInstant();
        Instant nextEnd = endsAtLocal == null ? null : endsAtLocal.atZone(zone).toInstant();
        boolean changed = !Objects.equals(nextStart, startsAt) || !Objects.equals(nextEnd, endsAt);
        startsAt = nextStart;
        endsAt = nextEnd;
        return changed;
    }

    public boolean changeStatus(EventStatus next) {
        boolean changed = next != status;
        status = Objects.requireNonNull(next);
        return changed;
    }

    public void softDelete() {
        super.delete(Instant.now());
    }

    public void markAlerted(LocalDate on) {
        lastAlertedOn = on;
    }

    private boolean apply(EventLockableField field, Object value) {
        boolean changed = switch (field) {
            case NAME -> set(Venue.requireText((String) value), name, v -> name = v);
            case VENUE -> {
                Venue next = (Venue) value;
                boolean c = !Objects.equals(next == null ? null : next.getId(), venueId());
                venue = next;
                yield c;
            }
            case EVENT_DATE -> moveDate((LocalDate) value);
            case STARTS_AT -> set(validStart((LocalDateTime) value), startsAtLocal, v -> startsAtLocal = v);
            case ENDS_AT -> set(validEnd((LocalDateTime) value), endsAtLocal, v -> endsAtLocal = v);
            case RUNNING_MINUTES -> set((Integer) value, runningMinutes, v -> runningMinutes = v);
            case TYPE -> set(Objects.requireNonNull((EventType) value), type, v -> type = v);
            case SEASON -> set((String) value, season, v -> season = v);
            case POSTER -> set((String) value, posterKey, v -> posterKey = v);
            case TICKET_URL -> set((String) value, ticketUrl, v -> ticketUrl = v);
            case DESCRIPTION -> set((String) value, description, v -> description = v);
            case TIMEZONE -> set(validZone((String) value), timezone, v -> timezone = v);
        };
        recomputeInstants();
        return changed;
    }

    private static <T> boolean set(T next, T current, java.util.function.Consumer<T> setter) {
        boolean changed = !Objects.equals(next, current);
        setter.accept(next);
        return changed;
    }

    // 날짜 이동: 벽시계 시각의 시각 부분은 유지하고 날짜만 옮긴다
    private boolean moveDate(LocalDate next) {
        Objects.requireNonNull(next, "event_date 는 null 일 수 없습니다");
        if (next.equals(eventDate)) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(eventDate, next);
        eventDate = next;
        if (startsAtLocal != null) {
            startsAtLocal = startsAtLocal.plusDays(days);
        }
        if (endsAtLocal != null) {
            endsAtLocal = endsAtLocal.plusDays(days);
        }
        return true;
    }

    private LocalDateTime validStart(LocalDateTime next) {
        if (next != null && !next.toLocalDate().equals(eventDate)) {
            throw new BadRequestException(ErrorCode.INVALID_EVENT_SCHEDULE, "시작 시각의 날짜가 행사 날짜와 다릅니다.");
        }
        if (next != null && endsAtLocal != null && endsAtLocal.isBefore(next)) {
            throw new BadRequestException(ErrorCode.INVALID_EVENT_SCHEDULE, "종료 시각이 시작 시각보다 앞섭니다.");
        }
        return next;
    }

    private LocalDateTime validEnd(LocalDateTime next) {
        if (next != null && startsAtLocal != null && next.isBefore(startsAtLocal)) {
            throw new BadRequestException(ErrorCode.INVALID_EVENT_SCHEDULE, "종료 시각이 시작 시각보다 앞섭니다.");
        }
        return next;
    }

    private static String validZone(String zone) {
        if (zone == null) {
            throw new BadRequestException(ErrorCode.INVALID_EVENT_SCHEDULE, "시간대는 비울 수 없습니다.");
        }
        try {
            return ZoneId.of(zone).getId();
        } catch (DateTimeException e) {
            throw new BadRequestException(ErrorCode.INVALID_EVENT_SCHEDULE, "알 수 없는 시간대입니다: " + zone);
        }
    }
}
