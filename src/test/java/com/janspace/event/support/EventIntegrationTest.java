package com.janspace.event.support;

import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.application.port.TimezonePort;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.EventRepository;
import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import com.janspace.event.domain.EventSourcePolicy.SourceVenue;
import com.janspace.event.domain.EventType;
import com.janspace.event.domain.GeoPoint;
import com.janspace.ops.application.port.AlertPort;
import com.janspace.support.IntegrationTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * event 통합 테스트 기반. 운영자 알림(AlertPort)·S3·시간대(TimezonePort)는 목으로 두고, 도메인 이벤트는 ApplicationEvents 로 센다.
 * 매 테스트 전에 event 테이블을 비운다.
 */
@RecordApplicationEvents
@TestPropertySource(properties = "janspace.storage.cdn-base-url=https://cdn.test/")
public abstract class EventIntegrationTest extends IntegrationTest {

    public static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    static final List<String> TABLES = List.of("event_proposals", "event_source_changes", "event_discoveries",
            "event_match_candidates", "event_sources", "event_translations", "events", "venue_sources",
            "venue_translations", "venues");

    @MockitoBean
    protected AlertPort alertPort;

    @MockitoBean
    protected S3Client s3Client;

    @MockitoBean
    protected TimezonePort timezonePort;

    @Autowired
    protected ApplicationEvents applicationEvents;

    @Autowired
    protected EventRepository eventRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanEventTables() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        TABLES.forEach(t -> jdbcTemplate.execute("TRUNCATE TABLE " + t));
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    protected Event newEvent(String name, LocalDate date) {
        return eventRepository.save(Event.create(name, SupportedLocale.KO, EventType.CONCERT, date));
    }

    // 시작 시각이 출처로 채워진 행사 (잠금 없음)
    protected Event newEventWithStart(String name, LocalDate date, LocalDateTime startsAtLocal) {
        Event event = Event.create(name, SupportedLocale.KO, EventType.CONCERT, date);
        event.applySource(EventLockableField.STARTS_AT, startsAtLocal);
        return eventRepository.save(event);
    }

    protected static SourceSnapshot snapshot(String externalId, String occurrenceKey, String name, LocalDateTime startsAtLocal,
                                             LocalDate eventDate, SourceVenue venue) {
        return new SourceSnapshot(externalId, occurrenceKey, name, null, null, null, venue, startsAtLocal, eventDate, null);
    }

    protected static SourceVenue sourceVenue(String externalId, String name, GeoPoint point) {
        return new SourceVenue(externalId, name, "서울", "KR", point);
    }

    protected long count(Class<?> eventType) {
        return applicationEvents.stream(eventType).count();
    }
}
