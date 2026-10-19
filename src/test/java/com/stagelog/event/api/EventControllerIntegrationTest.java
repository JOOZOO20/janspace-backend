package com.stagelog.event.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.EventQuery;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventStatus;
import com.stagelog.event.domain.EventTranslation;
import com.stagelog.event.domain.EventTranslationRepository;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.event.domain.TranslationField;
import com.stagelog.event.domain.TranslationSource;
import com.stagelog.event.domain.Venue;
import com.stagelog.event.domain.VenueLockableField;
import com.stagelog.event.domain.VenueRepository;
import com.stagelog.event.support.EventIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 공개 행사 API 의 JSON 형태(파생 status, HH:mm, LocalizedText)와 팬 제안 인증 규칙.
 */
class EventControllerIntegrationTest extends EventIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    WebApplicationContext context;
    @Autowired
    VenueRepository venueRepository;
    @Autowired
    EventTranslationRepository translationRepository;

    MockMvc mockMvc;
    Long upcomingId;
    Long pastId;
    Long cancelledId;

    // JWT 필터를 거치지 않고 AuthenticatedUser principal 을 직접 넣는다
    static RequestPostProcessor user(long userId) {
        Authentication auth = new UsernamePasswordAuthenticationToken(new AuthenticatedUser(userId, false), null, List.of());
        return authentication(auth);
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        LocalDate today = LocalDate.now(EventQuery.LIST_ZONE);

        Venue venue = Venue.create("KSPO DOME", SupportedLocale.KO, "서울", "KR");
        venue.adminUpdate(VenueLockableField.COORDINATES, new GeoPoint(37.5199, 127.1233));
        venueRepository.save(venue);

        Event upcoming = newEventWithStart("잔나비 단독 공연", today.plusDays(10), today.plusDays(10).atTime(19, 0));
        upcoming.applySource(EventLockableField.ENDS_AT, today.plusDays(10).atTime(21, 30));
        upcoming.applySource(EventLockableField.VENUE, venue);
        upcomingId = eventRepository.save(upcoming).getId();
        translationRepository.save(new EventTranslation(upcoming, TranslationField.NAME, SupportedLocale.EN, "Jannabi Solo Concert",
                TranslationSource.ADMIN, true));

        pastId = newEvent("지난 공연", today.minusDays(10)).getId();
        Event cancelled = newEvent("취소된 공연", today.plusDays(20));
        cancelled.changeStatus(EventStatus.CANCELLED);
        cancelledId = eventRepository.save(cancelled).getId();
        Event deleted = newEvent("삭제된 공연", today.plusDays(5));
        deleted.softDelete();
        eventRepository.save(deleted);
    }

    @Test
    @DisplayName("GET /events?tab=UPCOMING: 오늘 이후 오름차순, 삭제 제외, 취소 포함, 파생 status·HH:mm·LocalizedText")
    void upcoming_list_shape() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("tab", "UPCOMING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(upcomingId))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLETE"))
                .andExpect(jsonPath("$.data.content[0].kind").value("CONCERT"))
                .andExpect(jsonPath("$.data.content[0].startTime").value("19:00"))
                .andExpect(jsonPath("$.data.content[0].endTime").value("21:30"))
                .andExpect(jsonPath("$.data.content[0].timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.content[0].name.original").value("잔나비 단독 공연"))
                .andExpect(jsonPath("$.data.content[0].name.en").value("Jannabi Solo Concert"))
                .andExpect(jsonPath("$.data.content[0].name.ko").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].venue.name.original").value("KSPO DOME"))
                .andExpect(jsonPath("$.data.content[0].venue.city.original").value("서울"))
                .andExpect(jsonPath("$.data.content[0].venue.countryCode").value("KR"))
                .andExpect(jsonPath("$.data.content[0].venue.location.lat").value(37.5199))
                .andExpect(jsonPath("$.data.content[0].source.kopisId").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].starCount").value(0))
                .andExpect(jsonPath("$.data.content[0].my").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].id").value(cancelledId))
                .andExpect(jsonPath("$.data.content[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.content[1].venue").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].startTime").doesNotExist());
    }

    @Test
    @DisplayName("GET /events?tab=PAST 와 year 필터, 상세 404 EVENT_NOT_FOUND")
    void past_list_year_filter_and_detail() throws Exception {
        LocalDate today = LocalDate.now(EventQuery.LIST_ZONE);
        mockMvc.perform(get("/api/v1/events").param("tab", "PAST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(pastId))
                .andExpect(jsonPath("$.data.content[0].status").value("INCOMPLETE"));
        mockMvc.perform(get("/api/v1/events").param("tab", "PAST").param("year", String.valueOf(today.getYear() - 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        mockMvc.perform(get("/api/v1/events/{id}", upcomingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(upcomingId))
                .andExpect(jsonPath("$.data.date").value(today.plusDays(10).toString()));
        mockMvc.perform(get("/api/v1/events/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("팬 제안: 익명 POST 는 401 봉투, 로그인 사용자는 등록 후 /me/suggestions 에서 본인 것만 본다")
    void proposal_create_and_mine() throws Exception {
        insertConsentedUser(42L);
        insertConsentedUser(7L);
        String body = "{\"text\":\"시작 시각이 19:30 으로 바뀌었어요\"}";
        mockMvc.perform(post("/api/v1/events/{id}/suggestions", upcomingId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/events/{id}/suggestions", upcomingId).with(user(42L))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value(upcomingId))
                .andExpect(jsonPath("$.data.text").value("시작 시각이 19:30 으로 바뀌었어요"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("Z")));
        mockMvc.perform(post("/api/v1/events/{id}/suggestions", upcomingId).with(user(7L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"다른 사람 제안\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/events/{id}/suggestions", 999999).with(user(42L))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/events/{id}/suggestions", upcomingId).with(user(42L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        mockMvc.perform(get("/api/v1/me/suggestions").with(user(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].text").value("시작 시각이 19:30 으로 바뀌었어요"));
        mockMvc.perform(get("/api/v1/me/suggestions"))
                .andExpect(status().isUnauthorized());
    }

    // 동의 게이트(ConsentInterceptor)를 통과하려면 동의한 사용자 행이 있어야 한다
    private void insertConsentedUser(long id) {
        jdbcTemplate.update("INSERT INTO users (id, nickname, locale, is_admin, consented_at, terms_version, created_at, updated_at) "
                + "VALUES (?, ?, 'ko', FALSE, UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", id, "user" + id);
    }
}
