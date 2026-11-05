package com.stagelog.event.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.SourceSnapshotCodec;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventDiscovery;
import com.stagelog.event.domain.EventDiscoveryRepository;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventMatchCandidate;
import com.stagelog.event.domain.EventMatchCandidateRepository;
import com.stagelog.event.domain.EventProposal;
import com.stagelog.event.domain.EventProposalRepository;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.EventTranslationRepository;
import com.stagelog.event.domain.TranslationField;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.support.EventIntegrationTest;
import com.stagelog.support.WithAdmin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * 관리자 행사 API: 생성·수정·잠금 해제·제안 병합·발견 수락·후보 선택·포스터 업로드·공연장 수정.
 */
@WithAdmin
class AdminEventControllerIntegrationTest extends EventIntegrationTest {

    static final String CREATE_BODY = """
            {"name":{"original":"스타더스트 클럽 단독 공연"},"kind":"CONCERT","date":"2026-10-23","startTime":"19:00","endTime":"21:00",
             "venue":{"name":{"original":"KSPO DOME"},"city":{"original":"서울"},"countryCode":"KR","location":{"lat":37.5199,"lng":127.1233}},
             "season":"2026 전국투어"}""";

    @Autowired
    WebApplicationContext context;
    @Autowired
    EventProposalRepository proposalRepository;
    @Autowired
    EventDiscoveryRepository discoveryRepository;
    @Autowired
    EventMatchCandidateRepository candidateRepository;
    @Autowired
    EventSourceRepository sourceRepository;
    @Autowired
    SourceSnapshotCodec codec;
    @Autowired
    ApplySourcesUseCase applySources;
    @Autowired
    EventTranslationRepository translationRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        insertConsentedAdmin();
    }

    @Test
    @DisplayName("POST /admin/events: 생성 + 공연장 + 잠금, 응답은 COMPLETE. PATCH 는 준 필드만, unlock 은 잘못된 필드에 400")
    void create_patch_unlock() throws Exception {
        String response = mockMvc.perform(post("/api/v1/admin/events").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETE"))
                .andExpect(jsonPath("$.data.startTime").value("19:00"))
                .andExpect(jsonPath("$.data.endTime").value("21:00"))
                .andExpect(jsonPath("$.data.venue.location.lng").value(127.1233))
                .andExpect(jsonPath("$.data.season").value("2026 전국투어"))
                .andReturn().getResponse().getContentAsString();
        long id = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).get("data").get("id").asLong();
        Event created = eventRepository.findById(id).orElseThrow();
        assertThat(created.getLockedFields()).contains(EventLockableField.NAME, EventLockableField.STARTS_AT, EventLockableField.ENDS_AT,
                EventLockableField.VENUE, EventLockableField.SEASON);

        mockMvc.perform(patch("/api/v1/admin/events/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"20:00\",\"status\":\"CANCEL_SUSPECTED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startTime").value("20:00"))
                .andExpect(jsonPath("$.data.endTime").value("21:00"))
                .andExpect(jsonPath("$.data.status").value("CANCEL_SUSPECTED"));
        mockMvc.perform(patch("/api/v1/admin/events/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endTime\":\"18:00\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT_SCHEDULE"));

        mockMvc.perform(post("/api/v1/admin/events/{id}/unlock", id).contentType(MediaType.APPLICATION_JSON).content("{\"field\":\"BOGUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_FIELD_NOT_LOCKABLE"));
        mockMvc.perform(post("/api/v1/admin/events/{id}/unlock", id).contentType(MediaType.APPLICATION_JSON).content("{\"field\":\"SEASON\"}"))
                .andExpect(status().isOk());
        assertThat(eventRepository.findById(id).orElseThrow().isLocked(EventLockableField.SEASON)).isFalse();

        mockMvc.perform(post("/api/v1/admin/events").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"CONCERT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("예매 URL·설명·공연 시간: 생성·응답 노출·잠금, PATCH 키 없음 = 유지, null = 관리자 값 지우고 출처 값 따르기, 잘못된 값은 400")
    void ticket_description_running_minutes() throws Exception {
        String body = CREATE_BODY.replace("\"season\":\"2026 전국투어\"", "\"season\":\"2026 전국투어\",\"ticketUrl\":\"https://tickets.example/1\","
                + "\"description\":{\"original\":\"출연: 스타더스트 클럽\",\"en\":\"Cast: STARDUST CLUB\"},\"runningMinutes\":150");
        String response = mockMvc.perform(post("/api/v1/admin/events").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketUrl").value("https://tickets.example/1"))
                .andExpect(jsonPath("$.data.description.original").value("출연: 스타더스트 클럽"))
                .andExpect(jsonPath("$.data.description.en").value("Cast: STARDUST CLUB"))
                .andExpect(jsonPath("$.data.runningMinutes").value(150))
                .andExpect(jsonPath("$.data.venue.id").isNumber())
                .andReturn().getResponse().getContentAsString();
        long id = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response).get("data").get("id").asLong();
        assertThat(eventRepository.findById(id).orElseThrow().getLockedFields())
                .contains(EventLockableField.TICKET_URL, EventLockableField.DESCRIPTION, EventLockableField.RUNNING_MINUTES);
        // 출처(KOPIS)도 예매 URL 을 준다. 잠겨 있으니 관리자 값이 이긴다
        applySources.apply(id, Map.of(Provider.KOPIS, new SourceSnapshot("PF1", "2026-10-23", null, null, "https://kopis.example/t",
                null, null, null, DATE, null)));

        // 키 없음 = 유지
        mockMvc.perform(patch("/api/v1/admin/events/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"season\":\"앵콜\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketUrl").value("https://tickets.example/1"))
                .andExpect(jsonPath("$.data.description.original").value("출연: 스타더스트 클럽"))
                .andExpect(jsonPath("$.data.runningMinutes").value(150));

        // null = 관리자 값 지우기: 예매 URL 은 출처 값으로, 출처가 없는 설명·공연 시간은 비고 설명 번역도 지워진다
        mockMvc.perform(patch("/api/v1/admin/events/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketUrl\":null,\"description\":null,\"runningMinutes\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketUrl").value("https://kopis.example/t"))
                .andExpect(jsonPath("$.data.description").isEmpty())
                .andExpect(jsonPath("$.data.runningMinutes").isEmpty());
        Event cleared = eventRepository.findById(id).orElseThrow();
        assertThat(cleared.getLockedFields())
                .doesNotContain(EventLockableField.TICKET_URL, EventLockableField.DESCRIPTION, EventLockableField.RUNNING_MINUTES);
        assertThat(translationRepository.findAllByEventIdIn(List.of(id)))
                .noneMatch(t -> t.getField() == TranslationField.DESCRIPTION);

        // 신뢰 경계 검증: 스크립트 URL, 범위 밖 공연 시간, 빈 설명
        for (String bad : List.of("{\"ticketUrl\":\"javascript:alert(1)\"}", "{\"runningMinutes\":0}",
                "{\"description\":{\"original\":\" \"}}")) {
            mockMvc.perform(patch("/api/v1/admin/events/{id}", id).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Test
    @DisplayName("GET /admin/suggestions 는 팬 제안과 발견을 합쳐 최신순, FAN 상태 변경, 발견 수락은 새 행사 생성 + 출처 연결")
    void suggestions_merged_and_discovery_accept() throws Exception {
        Event event = newEvent("스타더스트 클럽 단독 공연", DATE);
        EventProposal proposal = proposalRepository.save(new EventProposal(event, 42L, "공연장이 바뀌었어요"));
        EventDiscovery discovery = discoveryRepository.save(new EventDiscovery(Provider.KOPIS, "PF9", "2026-11-01#19:00",
                codec.write(snapshot("PF9", "2026-11-01#19:00", "스타더스트 클럽 부산 공연", DATE.plusDays(9).atTime(19, 0), DATE.plusDays(9),
                        sourceVenue("FC7", "벡스코", new GeoPoint(35.169, 129.136)))),
                DATE.plusDays(9), "벡스코", "부산"));

        mockMvc.perform(get("/api/v1/admin/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].kind").value("KOPIS_EVENT"))
                .andExpect(jsonPath("$.data[0].id").value(discovery.getId()))
                .andExpect(jsonPath("$.data[0].externalId").value("PF9"))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].draft.name.original").value("스타더스트 클럽 부산 공연"))
                .andExpect(jsonPath("$.data[0].draft.date").value("2026-11-01"))
                .andExpect(jsonPath("$.data[0].draft.startTime").value("19:00"))
                .andExpect(jsonPath("$.data[0].draft.venue.name.original").value("벡스코"))
                .andExpect(jsonPath("$.data[0].suggestion").doesNotExist())
                .andExpect(jsonPath("$.data[1].kind").value("FAN"))
                .andExpect(jsonPath("$.data[1].id").value(proposal.getId()))
                .andExpect(jsonPath("$.data[1].suggestion.text").value("공연장이 바뀌었어요"))
                .andExpect(jsonPath("$.data[1].draft").doesNotExist());

        mockMvc.perform(post("/api/v1/admin/suggestions/{id}", proposal.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"HELD\"}"))
                .andExpect(status().isOk());
        assertThat(proposalRepository.findById(proposal.getId()).orElseThrow().getStatus().name()).isEqualTo("HELD");
        mockMvc.perform(post("/api/v1/admin/suggestions/{id}", 999999).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"HELD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUGGESTION_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/admin/discoveries/{id}/accept", discovery.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name.original").value("스타더스트 클럽 부산 공연"))
                .andExpect(jsonPath("$.data.date").value("2026-11-01"))
                .andExpect(jsonPath("$.data.startTime").value("19:00"))
                .andExpect(jsonPath("$.data.source.kopisId").value("PF9"))
                .andExpect(jsonPath("$.data.venue.name.original").value("벡스코"))
                .andExpect(jsonPath("$.data.status").value("INCOMPLETE"));
        assertThat(discoveryRepository.findById(discovery.getId()).orElseThrow().getStatus().name()).isEqualTo("ACCEPTED");
        mockMvc.perform(post("/api/v1/admin/discoveries/{id}/dismiss", discovery.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("후보 선택: 고른 후보가 출처가 되고 나머지 후보는 삭제. /sources 에 반영")
    void candidate_select_links_source() throws Exception {
        Event event = newEvent("스타더스트 클럽 단독 공연", DATE);
        EventMatchCandidate chosen = candidateRepository.save(new EventMatchCandidate(event, Provider.KOPIS, "PF1", "2026-10-23",
                codec.write(snapshot("PF1", "2026-10-23", "스타더스트 클럽 단독 공연", DATE.atTime(19, 0), DATE, null))));
        candidateRepository.save(new EventMatchCandidate(event, Provider.KOPIS, "PF2", "2026-10-23",
                codec.write(snapshot("PF2", "2026-10-23", "다른 공연", null, DATE, null))));

        mockMvc.perform(get("/api/v1/admin/events/{id}/sources", event.getId()))
                .andExpect(jsonPath("$.data.sources.length()").value(0))
                .andExpect(jsonPath("$.data.candidates.length()").value(2));

        mockMvc.perform(post("/api/v1/admin/events/{id}/candidates/{cid}/select", event.getId(), chosen.getId()))
                .andExpect(status().isOk());

        assertThat(candidateRepository.count()).isZero();
        assertThat(sourceRepository.findByEventIdAndProvider(event.getId(), Provider.KOPIS).orElseThrow().getExternalId()).isEqualTo("PF1");
        mockMvc.perform(get("/api/v1/admin/events/{id}/sources", event.getId()))
                .andExpect(jsonPath("$.data.sources[0].provider").value("KOPIS"))
                .andExpect(jsonPath("$.data.sources[0].externalId").value("PF1"))
                .andExpect(jsonPath("$.data.candidates.length()").value(0))
                .andExpect(jsonPath("$.data.pendingChanges.length()").value(0))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"));
        mockMvc.perform(post("/api/v1/admin/events/{id}/candidates/{cid}/select", event.getId(), 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CANDIDATE_NOT_FOUND"));
    }

    @Test
    @DisplayName("포스터 업로드는 S3 에 넣고 POSTER 를 잠근다. 이미지가 아니면 400 POSTER_INVALID")
    void poster_upload() throws Exception {
        org.mockito.Mockito.when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        Event event = newEvent("스타더스트 클럽 단독 공연", DATE);
        MockMultipartFile file = new MockMultipartFile("file", "poster.png", "image/png", new byte[] {1, 2, 3});

        mockMvc.perform(multipart("/api/v1/admin/events/{id}/poster", event.getId()).file(file).with(r -> { r.setMethod("PUT"); return r; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://cdn.test/events/" + event.getId() + "/poster.png"));

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        Event updated = eventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getPosterKey()).isEqualTo("events/" + event.getId() + "/poster.png");
        assertThat(updated.isLocked(EventLockableField.POSTER)).isTrue();
        mockMvc.perform(get("/api/v1/events/{id}", event.getId()))
                .andExpect(jsonPath("$.data.posterUrl").value("https://cdn.test/events/" + event.getId() + "/poster.png"));

        MockMultipartFile text = new MockMultipartFile("file", "x.txt", "text/plain", new byte[] {1});
        mockMvc.perform(multipart("/api/v1/admin/events/{id}/poster", event.getId()).file(text).with(r -> { r.setMethod("PUT"); return r; }))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POSTER_INVALID"));
    }

    @Test
    @DisplayName("번역 upsert 와 공연장 수정, 관리자 아닌 사용자는 403")
    void translation_and_venue_update() throws Exception {
        mockMvc.perform(post("/api/v1/admin/events").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isOk());
        Event event = eventRepository.findAll().get(0);

        mockMvc.perform(put("/api/v1/admin/events/{id}/translations", event.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"NAME\",\"locale\":\"zh-TW\",\"text\":\"簡單 單獨公演\",\"reviewed\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events/{id}", event.getId()))
                .andExpect(jsonPath("$.data.name['zh-TW']").value("簡單 單獨公演"));

        mockMvc.perform(patch("/api/v1/admin/venues/{id}", event.venueId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\":\"Asia/Tokyo\",\"city\":\"도쿄\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events/{id}", event.getId()))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.data.venue.city.original").value("도쿄"));
        mockMvc.perform(patch("/api/v1/admin/venues/{id}", 999999).contentType(MediaType.APPLICATION_JSON).content("{\"city\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VENUE_NOT_FOUND"));
    }

    @Test
    @WithMockUser
    @DisplayName("ADMIN 권한 없으면 403 봉투")
    void non_admin_is_403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/suggestions"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
