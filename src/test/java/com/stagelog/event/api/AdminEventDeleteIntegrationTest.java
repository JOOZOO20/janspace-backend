package com.stagelog.event.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.EventQuery;
import com.stagelog.event.domain.DiscoveryStatus;
import com.stagelog.event.domain.EventDiscovery;
import com.stagelog.event.domain.EventDiscoveryRepository;
import com.stagelog.event.domain.EventProposal;
import com.stagelog.event.domain.EventProposalRepository;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.support.EventIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * DELETE /admin/events/{id}: 실제 흐름으로 검증한다. 관리자 생성 -> 푸시 잡 생성(알림 리스너) -> 참석자 없는 행사 삭제는 행사 소유 데이터와
 * 푸시 잡(ON DELETE CASCADE)까지 지워지고, 갈래요·기록이 연결된 행사는 attendances 외래 키가 막아 409 EVENT_IN_USE + 전부 되돌림.
 */
@WithMockUser(authorities = "ADMIN")
class AdminEventDeleteIntegrationTest extends EventIntegrationTest {

    static final long USER_ID = 900_001L;
    static final String BODY = """
            {"name":{"original":"스타더스트 클럽 단독 공연"},"kind":"CONCERT","date":"%s","startTime":"19:00","endTime":"21:00",
             "venue":{"name":{"original":"KSPO DOME"},"countryCode":"KR","location":{"lat":37.5199,"lng":127.1233}}}""";

    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplySourcesUseCase applySources;
    @Autowired
    EventProposalRepository proposalRepository;
    @Autowired
    EventDiscoveryRepository discoveryRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        startEventIdsAboveStaleReferences();
        jdbc.update("INSERT INTO users (id, nickname, locale, is_admin, departure_name, departure_country_code, departure_lat, departure_lng, "
                + "consented_at, terms_version, created_at, updated_at) VALUES (?, 'fan', 'ko', FALSE, '서울 중구', 'KR', 37.5636, 126.9976, "
                + "UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", USER_ID);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM attendances WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID);
    }

    static RequestPostProcessor fan() {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(USER_ID, false), null, List.of()));
    }

    private long create(LocalDate date) throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/events").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted(date)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long id = JsonMapper.builder().build().readTree(response).get("data").get("id").asLong();
        mockMvc.perform(put("/api/v1/admin/events/{id}/translations", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"NAME\",\"locale\":\"en\",\"text\":\"Stardust Club Solo Concert\",\"reviewed\":true}"))
                .andExpect(status().isOk());
        return id;
    }

    private int count(String table, long eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE event_id = ?", Integer.class, eventId);
    }

    @Test
    @DisplayName("참석자 없는 행사: 삭제 성공. 번역·출처·팬 제안·푸시 잡이 함께 지워지고 출처 회차는 DISMISSED 발견으로 남는다. 다시 지우면 404")
    void delete_unreferenced_event() throws Exception {
        long id = create(DATE);
        applySources.apply(id, Map.of(Provider.KOPIS, snapshot("PF1", "2026-10-23#19:00", "스타더스트 클럽 단독 공연", null, DATE, null)));
        proposalRepository.save(new EventProposal(eventRepository.findById(id).orElseThrow(), USER_ID, "시작 시각이 바뀌었어요"));
        assertThat(count("push_jobs", id)).as("행사 생성으로 알림 잡이 예약된다").isEqualTo(1);
        assertThat(count("event_sources", id)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/admin/events/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        assertThat(eventRepository.findById(id)).isEmpty();
        assertThat(count("push_jobs", id)).isZero();
        assertThat(count("event_translations", id)).isZero();
        assertThat(count("event_sources", id)).isZero();
        assertThat(count("event_proposals", id)).isZero();
        EventDiscovery suppressed = discoveryRepository.findByProviderAndExternalIdAndOccurrenceKey(Provider.KOPIS, "PF1", "2026-10-23#19:00")
                .orElseThrow();
        assertThat(suppressed.getStatus()).isEqualTo(DiscoveryStatus.DISMISSED);
        assertThat(suppressed.getEvent()).isNull();

        mockMvc.perform(get("/api/v1/events/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/admin/events/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("갈래요가 있는 행사와 기록이 있는 행사: 409 EVENT_IN_USE, 행사·번역·푸시 잡은 그대로 (전체 롤백)")
    void delete_referenced_event_is_conflict() throws Exception {
        long going = create(DATE);
        mockMvc.perform(post("/api/v1/events/{id}/going", going).with(fan()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.my.status").value("GOING"));

        mockMvc.perform(delete("/api/v1/admin/events/{id}", going))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_IN_USE"));

        assertThat(eventRepository.findById(going)).isPresent();
        assertThat(count("event_translations", going)).isEqualTo(1);
        assertThat(count("push_jobs", going)).isEqualTo(1);

        long recorded = create(LocalDate.now(EventQuery.LIST_ZONE).minusDays(3));
        mockMvc.perform(post("/api/v1/records").with(fan()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":" + recorded + ",\"emotions\":[\"HAPPY\"],\"isRetro\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/admin/events/{id}", recorded))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_IN_USE"));
        assertThat(eventRepository.findById(recorded)).isPresent();
    }
}
