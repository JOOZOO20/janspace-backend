package com.janspace.attendance.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import com.janspace.attendance.domain.Attendance;
import com.janspace.attendance.domain.AttendanceRepository;
import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.GeoPoint;
import com.janspace.event.domain.Venue;
import com.janspace.event.domain.VenueLockableField;
import com.janspace.event.domain.VenueRepository;
import com.janspace.event.support.EventIntegrationTest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * attendance 통합 테스트 기반. 사용자는 users 행을 직접 넣고(동의 게이트·출발지), 인증은 principal 주입, 행사·공연장은 event 저장소로 만든다.
 * attendances 는 users·events·songs 에 FK 가 있으므로 전후로 지워 다른 기능 테스트의 정리를 막지 않는다.
 */
@AutoConfigureMockMvc
public abstract class AttendanceIntegrationTest extends EventIntegrationTest {

    // 서울 시청 / KSPO 돔 / 싱가포르 / 방콕 / 쿠알라룸푸르 / 도쿄
    public static final double[] SEOUL = {37.5665, 126.9780};
    public static final double[] KSPO = {37.5199, 127.1233};
    public static final double[] SINGAPORE = {1.3521, 103.8198};
    public static final double[] BANGKOK = {13.7563, 100.5018};
    public static final double[] KUALA_LUMPUR = {3.1390, 101.6869};
    public static final double[] TOKYO = {35.6762, 139.6503};

    protected static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected AttendanceRepository attendances;
    @Autowired
    protected VenueRepository venueRepository;
    @Autowired
    protected ApplicationEventPublisher publisher;

    private final List<Long> songIds = new ArrayList<>();

    @BeforeEach
    void cleanAttendanceTables() {
        clean();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    private void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.execute("TRUNCATE TABLE attendances");
        jdbc.update("DELETE FROM users");
        songIds.forEach(id -> jdbc.update("DELETE FROM songs WHERE id = ?", id));
        songIds.clear();
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        applicationEvents.clear();
    }

    protected static RequestPostProcessor user(long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(userId, false), null, List.of()));
    }

    // 동의한 사용자. departure 가 null 이면 출발지 없음
    protected void insertUser(long id, double[] departure) {
        jdbc.update("INSERT INTO users (id, nickname, locale, is_admin, departure_name, departure_country_code, departure_lat, departure_lng, "
                        + "consented_at, terms_version, created_at, updated_at) VALUES (?, ?, 'ko', FALSE, ?, ?, ?, ?, UTC_TIMESTAMP(6), 'v1', "
                        + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id, "user" + id, departure == null ? null : "서울 중구", departure == null ? null : "KR",
                departure == null ? null : departure[0], departure == null ? null : departure[1]);
    }

    protected long insertSong(String title) {
        jdbc.update("INSERT INTO songs (title, original_locale, created_at, updated_at) VALUES (?, 'ko', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", title);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM songs", Long.class);
        songIds.add(id);
        return id;
    }

    protected Venue venue(String name, String city, String countryCode, double[] coords) {
        Venue venue = Venue.create(name, SupportedLocale.KO, city, countryCode);
        if (coords != null) {
            venue.adminUpdate(VenueLockableField.COORDINATES, new GeoPoint(coords[0], coords[1]));
        }
        return venueRepository.save(venue);
    }

    protected Event event(String name, LocalDate date, Venue venue) {
        Event event = newEvent(name, date);
        if (venue != null) {
            event.applySource(EventLockableField.VENUE, venue);
        }
        return eventRepository.save(event);
    }

    protected Attendance row(long userId, long eventId) {
        return attendances.findByUserIdAndEventId(userId, eventId).orElseThrow();
    }

    protected static String recordBody(long eventId, String emotionsJson, boolean retro, Boolean chained, Long songId) {
        return "{\"eventId\":" + eventId + ",\"emotions\":" + emotionsJson + ",\"isRetro\":" + retro
                + (chained == null ? "" : ",\"chainedFromPrevious\":" + chained)
                + (songId == null ? "" : ",\"songId\":" + songId) + "}";
    }
}
