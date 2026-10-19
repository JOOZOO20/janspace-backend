package com.stagelog.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.Provider;
import com.stagelog.notification.application.DispatchDuePushJobs;
import com.stagelog.notification.application.DispatchDuePushJobs.CycleResult;
import com.stagelog.notification.application.port.PushSenderPort.PushSubscriptionData;
import com.stagelog.notification.application.port.PushSenderPort.SendResult;
import com.stagelog.support.Fixtures;
import com.jayway.jsonpath.DocumentContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 푸시 E2E: 코어 루프 -> RECORD 잡 -> 배달, 공연장 시간대 수정 -> 배달 예정 시각 이동,
 * 셋리 확정 -> 최고의 곡 미선택자만, 행사 취소 -> 잡 취소. 전송(PushSenderPort)은 목이고 디스패처는 runCycle(now) 로 직접 돌린다.
 */
class PushFlowIntegrationTest extends E2eIntegrationTest {

    static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    static final String SETLISTFM = "https://api.setlist.fm/rest/1.0/setlist/63a1b2c3";

    @Autowired
    DispatchDuePushJobs dispatcher;
    @Autowired
    EventSourceRepository eventSources;

    @BeforeEach
    void senderAccepts() {
        doReturn(new SendResult(201, null)).when(sender).send(any(), any());
    }

    @Test
    @DisplayName("A. 행사 생성 -> RECORD 잡(종료+10분) -> 로그인·구독·갈래요 -> due 전 0건, due 후 그 구독에 1건 SENT, 잡 DONE, 다음 사이클 0건")
    void core_loop_to_push() throws Exception {
        Session admin = loginAsAdmin("naver-userinfo-n2.json");
        LocalDate date = TODAY.plusDays(1);
        long eventId = createEvent(admin, "내일 공연", date, "KSPO DOME");

        Map<String, Object> job = job(eventId, "RECORD");
        Instant dueAt = dueAt(admin, job);
        assertThat(job.get("status")).isEqualTo("SCHEDULED");
        assertThat(dueAt).isEqualTo(date.atTime(21, 0).atZone(SEOUL_ZONE).toInstant().plus(Duration.ofMinutes(10)));

        Session fan = loginAndConsent("naver-userinfo-n1.json");
        setDeparture(fan);
        subscribe(fan, "https://push.example/fan");
        mvc.perform(post("/api/v1/events/{id}/going", eventId).with(bearer(fan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.my.status").value("GOING"));
        long subscriptionId = jdbc.queryForObject("SELECT id FROM push_subscriptions WHERE endpoint = ?", Long.class, "https://push.example/fan");

        CycleResult early = dispatcher.runCycle(dueAt.minusSeconds(1));
        assertThat(early.claimed()).isZero();
        assertThat(early.sent()).isZero();
        verify(sender, never()).send(any(), any());

        CycleResult due = dispatcher.runCycle(dueAt.plusSeconds(1));
        assertThat(due.claimed()).isEqualTo(1);
        assertThat(due.sent()).isEqualTo(1);
        assertThat(due.completed()).isEqualTo(1);

        ArgumentCaptor<PushSubscriptionData> target = ArgumentCaptor.forClass(PushSubscriptionData.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(target.capture(), payload.capture());
        assertThat(target.getValue().endpoint()).isEqualTo("https://push.example/fan");
        assertThat(target.getValue().p256dh()).isEqualTo("p256dh-fan");
        assertThat(payload.getValue())
                .contains("\"kind\":\"RECORD_REMINDER\"")
                .contains("\"eventId\":" + eventId)
                .contains("\"title\":\"내일 공연\"")
                .contains("\"body\":\"공연은 어땠나요? 오늘의 별을 남겨 주세요.\"")
                .contains("\"url\":\"/events/" + eventId + "\"");

        mvc.perform(get("/api/v1/admin/push-jobs/{id}", job.get("id")).with(bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.deliveries.length()").value(1))
                .andExpect(jsonPath("$.data.deliveries[0].status").value("SENT"));
        assertThat(jdbc.queryForList("SELECT subscription_id, user_id, status, attempts FROM push_deliveries WHERE job_id = ?", job.get("id")))
                .singleElement().satisfies(d -> {
                    assertThat(d.get("subscription_id")).isEqualTo(subscriptionId);
                    assertThat(d.get("user_id")).isEqualTo(fan.userId());
                    assertThat(d.get("status")).isEqualTo("SENT");
                    assertThat(d.get("attempts")).isEqualTo(1);
                });

        CycleResult again = dispatcher.runCycle(dueAt.plusSeconds(120));
        assertThat(again).isEqualTo(new CycleResult(0, 0, 0, 0, 0, 0));
        verify(sender, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("B. 공연장 시간대 Asia/Seoul -> Asia/Bangkok: 행사 ends_at 과 RECORD 잡 due_at 이 같은 id 로 +2h. TIMEZONE 이 잠긴 행사는 그대로")
    void venue_timezone_change_moves_due_at() throws Exception {
        Session admin = loginAsAdmin("naver-userinfo-n2.json");
        LocalDate date = TODAY.plusDays(2);
        long open = createEvent(admin, "방콕 1일차", date, "임팩트 아레나");
        long locked = createEvent(admin, "방콕 2일차", date.plusDays(1), "임팩트 아레나");
        // 관리자 시간대 잠금은 HTTP 입력이 없으므로 도메인으로 잠근다
        Event lockedEvent = eventRepository.findById(locked).orElseThrow();
        lockedEvent.adminUpdate(EventLockableField.TIMEZONE, "Asia/Seoul");
        eventRepository.save(lockedEvent);
        long venueId = jdbc.queryForObject("SELECT venue_id FROM events WHERE id = ?", Long.class, open);
        assertThat(jdbc.queryForObject("SELECT venue_id FROM events WHERE id = ?", Long.class, locked)).isEqualTo(venueId);

        Map<String, Object> openJob = job(open, "RECORD");
        Map<String, Object> lockedJob = job(locked, "RECORD");
        Instant openDueBefore = dueAt(admin, openJob);
        Instant lockedDueBefore = dueAt(admin, lockedJob);
        LocalDateTime openEndsBefore = endsAt(open);
        LocalDateTime lockedEndsBefore = endsAt(locked);

        mvc.perform(patch("/api/v1/admin/venues/{id}", venueId).with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"timezone\":\"Asia/Bangkok\"}")).andExpect(status().isOk());

        mvc.perform(get("/api/v1/events/{id}", open)).andExpect(jsonPath("$.data.timezone").value("Asia/Bangkok"))
                .andExpect(jsonPath("$.data.endTime").value("21:00"));
        mvc.perform(get("/api/v1/events/{id}", locked)).andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"));
        assertThat(endsAt(open)).isEqualTo(openEndsBefore.plusHours(2));
        assertThat(endsAt(locked)).isEqualTo(lockedEndsBefore);

        Map<String, Object> openAfter = job(open, "RECORD");
        assertThat(openAfter.get("id")).isEqualTo(openJob.get("id"));
        assertThat(openAfter.get("status")).isEqualTo("SCHEDULED");
        assertThat(dueAt(admin, openAfter)).isEqualTo(openDueBefore.plus(Duration.ofHours(2)));
        Map<String, Object> lockedAfter = job(locked, "RECORD");
        assertThat(lockedAfter.get("id")).isEqualTo(lockedJob.get("id"));
        assertThat(dueAt(admin, lockedAfter)).isEqualTo(lockedDueBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_jobs WHERE event_id IN (?, ?)", Integer.class, open, locked)).isEqualTo(2);
    }

    @Test
    @DisplayName("C. 두 기록자(최고의 곡 있음/없음) -> setlist.fm 병합 -> SETLIST_CONFIRMED 잡 -> 곡을 고르지 않은 사용자에게만 배달")
    void setlist_merge_notifies_only_undecided() throws Exception {
        Session chooser = loginAsAdmin("naver-userinfo-n2.json");
        Session undecided = loginAndConsent("naver-userinfo-n1.json");
        setDeparture(chooser);
        setDeparture(undecided);
        subscribe(chooser, "https://push.example/chooser");
        subscribe(undecided, "https://push.example/undecided");

        long song = json(mvc.perform(put("/api/v1/admin/songs").with(bearer(chooser)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":{\"original\":\"떠도는 별들을 위해\"},\"aliases\":[\"For Stars Who Wander\"]}"))
                .andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
        mvc.perform(put("/api/v1/admin/songs").with(bearer(chooser)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":{\"original\":\"백일몽\"},\"aliases\":[\"daydreams\"]}")).andExpect(status().isOk());
        LocalDate date = TODAY.minusDays(1);
        long eventId = createEvent(chooser, "지난 공연", date, "KSPO DOME");
        eventSources.save(new EventSource(eventRepository.findById(eventId).orElseThrow(), Provider.SETLISTFM, "63a1b2c3",
                date.toString(), "{}", Instant.now()));

        mvc.perform(post("/api/v1/records").with(bearer(chooser)).contentType(MediaType.APPLICATION_JSON)
                .content(recordBody(eventId, "[\"HAPPY\"]", false, null, song))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/records").with(bearer(undecided)).contentType(MediaType.APPLICATION_JSON)
                .content(recordBody(eventId, "[\"HAPPY\"]", false, null, null))).andExpect(status().isOk());
        // 지난 행사의 RECORD 잡(종료+10분, 이미 지남)은 이 시나리오 밖이다. 먼저 흘려보내고 전송 기록을 비운다
        dispatcher.runCycle(Instant.now());
        assertThat(job(eventId, "RECORD").get("status")).isEqualTo("DONE");
        clearInvocations(sender);

        providers.expect(requestTo(SETLISTFM))
                .andRespond(withSuccess(Fixtures.read("setlistfm/setlist.json"), MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/v1/admin/events/{id}/setlist/merge", eventId).with(bearer(chooser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true));
        providers.verify();
        mvc.perform(get("/api/v1/events/{id}/setlist", eventId))
                .andExpect(jsonPath("$.data.entries.length()").value(2))
                .andExpect(jsonPath("$.data.entries[0].songId").value(song));

        Map<String, Object> job = job(eventId, "SETLIST_CONFIRMED");
        assertThat(job.get("status")).isEqualTo("SCHEDULED");
        CycleResult result = dispatcher.runCycle(Instant.now());
        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);

        ArgumentCaptor<PushSubscriptionData> target = ArgumentCaptor.forClass(PushSubscriptionData.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(target.capture(), payload.capture());
        assertThat(target.getValue().endpoint()).isEqualTo("https://push.example/undecided");
        assertThat(payload.getValue())
                .contains("\"kind\":\"SETLIST_CONFIRMED\"")
                .contains("\"eventId\":" + eventId)
                .contains("\"title\":\"지난 공연\"")
                .contains("\"body\":\"셋리스트가 확정됐어요. 최고의 곡을 골라 주세요.\"")
                .contains("\"url\":\"/events/" + eventId + "\"");
        assertThat(jdbc.queryForList("SELECT user_id FROM push_deliveries WHERE job_id = ?", Long.class, job.get("id")))
                .containsExactly(undecided.userId());
        assertThat(job(eventId, "SETLIST_CONFIRMED").get("status")).isEqualTo("DONE");
    }

    @Test
    @DisplayName("D. SCHEDULED RECORD 잡이 있는 행사를 관리자가 CANCELLED 로 바꾸면 잡 CANCELLED, due 뒤 사이클도 0건")
    void cancelled_event_cancels_job() throws Exception {
        Session admin = loginAsAdmin("naver-userinfo-n2.json");
        Session fan = loginAndConsent("naver-userinfo-n1.json");
        setDeparture(fan);
        long eventId = createEvent(admin, "취소될 공연", TODAY.plusDays(1), "KSPO DOME");
        subscribe(fan, "https://push.example/fan");
        mvc.perform(post("/api/v1/events/{id}/going", eventId).with(bearer(fan))).andExpect(status().isOk());
        Map<String, Object> job = job(eventId, "RECORD");
        Instant dueAt = dueAt(admin, job);
        assertThat(job.get("status")).isEqualTo("SCHEDULED");

        mvc.perform(patch("/api/v1/admin/events/{id}", eventId).with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(job(eventId, "RECORD").get("status")).isEqualTo("CANCELLED");
        CycleResult result = dispatcher.runCycle(dueAt.plusSeconds(1));
        assertThat(result.sent()).isZero();
        assertThat(result.claimed()).isZero();
        verify(sender, never()).send(any(), any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM push_deliveries", Integer.class)).isZero();
        mvc.perform(get("/api/v1/admin/push-jobs/{id}", job.get("id")).with(bearer(admin)))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    // 종료 21:00(현지)·좌표 있는 공연장. 공연장 timezone 은 TimezonePort 목이 비어 있어 행사 기본값(Asia/Seoul)을 쓴다
    private long createEvent(Session admin, String name, LocalDate date, String venueName) throws Exception {
        return json(mvc.perform(post("/api/v1/admin/events").with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":{\"original\":\"" + name + "\"},\"kind\":\"CONCERT\",\"date\":\"" + date
                                + "\",\"startTime\":\"19:00\",\"endTime\":\"21:00\",\"venue\":{\"name\":{\"original\":\"" + venueName
                                + "\"},\"city\":{\"original\":\"서울\"},\"countryCode\":\"KR\",\"location\":{\"lat\":37.5199,\"lng\":127.1233}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))
                .andReturn()).read("$.data.id", Long.class);
    }

    private void setDeparture(Session s) throws Exception {
        mvc.perform(patch("/api/v1/me").with(bearer(s)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departure\":{\"name\":\"서울 중구\",\"countryCode\":\"KR\",\"lat\":37.5665,\"lng\":126.9780}}"))
                .andExpect(status().isOk());
    }

    private void subscribe(Session s, String endpoint) throws Exception {
        String key = endpoint.substring(endpoint.lastIndexOf('/') + 1);
        mvc.perform(post("/api/v1/push/subscriptions").with(bearer(s)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"" + endpoint + "\",\"expirationTime\":null,\"keys\":{\"p256dh\":\"p256dh-" + key
                                + "\",\"auth\":\"auth-" + key + "\"}}"))
                .andExpect(status().isOk());
    }

    private Map<String, Object> job(long eventId, String kind) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, status FROM push_jobs WHERE event_id = ? AND kind = ?", eventId, kind);
        assertThat(rows).as("push_jobs eventId=%d kind=%s", eventId, kind).hasSize(1);
        return rows.get(0);
    }

    // 배달 예정 시각은 관리자 API 의 dueAt(UTC ISO) 로 읽는다
    private Instant dueAt(Session admin, Map<String, Object> job) throws Exception {
        DocumentContext body = json(mvc.perform(get("/api/v1/admin/push-jobs/{id}", job.get("id")).with(bearer(admin)))
                .andExpect(status().isOk()).andReturn());
        return Instant.parse(body.read("$.data.dueAt", String.class));
    }

    private LocalDateTime endsAt(long eventId) {
        return jdbc.queryForObject("SELECT ends_at FROM events WHERE id = ?", LocalDateTime.class, eventId);
    }
}
