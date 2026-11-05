package com.stagelog.event.support;

import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.port.TimezonePort;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourcePolicy.SourceVenue;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.ops.application.port.AlertPort;
import com.stagelog.support.IntegrationTest;
import com.stagelog.support.WithAdmin;
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
@TestPropertySource(properties = "stagelog.storage.cdn-base-url=https://cdn.test/")
public abstract class EventIntegrationTest extends IntegrationTest {

    public static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    static final List<String> TABLES = List.of("event_proposals", "event_source_changes", "event_discoveries",
            "event_match_candidates", "event_sources", "event_translations", "events", "venue_sources",
            "venue_translations", "venues");

    @MockitoBean
    protected AlertPort alertPort;


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

    // 다른 기능 테스트가 남긴 참조 행(attendances·setlist_pages·push_jobs)은 TRUNCATE 로 재사용되는 작은 행사 ID 를 가리킬 수 있다.
    // 행사 삭제처럼 그 외래 키에 반응하는 테스트는 남의 행을 지우지 않고 행사 ID 시작 값만 올려 겹치지 않게 한다
    protected void startEventIdsAboveStaleReferences() {
        jdbcTemplate.execute("ALTER TABLE events AUTO_INCREMENT = 900000");
    }

    // @WithAdmin 관리자의 동의한 사용자 행 (동의 게이트 통과용). 다른 테스트가 users 를 비우거나 남길 수 있어 지우고 넣는다
    protected void insertConsentedAdmin() {
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", WithAdmin.ID);
        jdbcTemplate.update("INSERT INTO users (id, nickname, locale, is_admin, consented_at, terms_version, created_at, updated_at) "
                + "VALUES (?, 'admin', 'ko', TRUE, UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", WithAdmin.ID);
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
        return new SourceVenue(externalId, name, "서울", "KR", point, null);
    }

    protected long count(Class<?> eventType) {
        return applicationEvents.stream(eventType).count();
    }
}
