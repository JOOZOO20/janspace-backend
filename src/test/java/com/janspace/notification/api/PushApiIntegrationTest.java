package com.janspace.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.domain.Event;
import com.janspace.notification.domain.PushDelivery;
import com.janspace.notification.domain.PushDeliveryStatus;
import com.janspace.notification.domain.PushJob;
import com.janspace.notification.domain.PushJobStatus;
import com.janspace.notification.domain.PushKind;
import com.janspace.notification.support.NotificationIntegrationTest;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 공개 API(구독 등록·해지·VAPID 공개키)와 관리자 API(잡 목록·상세·수동 재시도·즉시 디스패치) + 권한.
 */
class PushApiIntegrationTest extends NotificationIntegrationTest {

    static final long U1 = 41L;
    static final long U2 = 42L;
    static final String BODY = "{\"endpoint\":\"https://push.example/api/1\",\"expirationTime\":null,"
            + "\"keys\":{\"p256dh\":\"BNcRd\",\"auth\":\"tBHI\"}}";

    @BeforeEach
    void users() {
        insertUser(U1, SEOUL);
        insertUser(U2, SEOUL);
        insertUser(ADMIN_ID, null);
    }

    @Test
    @DisplayName("GET /push/vapid-public-key 는 공개, POST /push/subscriptions 는 로그인 필수. 같은 endpoint 두 번 -> 한 행, 키 갱신")
    void public_api() throws Exception {
        mvc.perform(get("/api/v1/push/vapid-public-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicKey").value("test-vapid-public-key"));

        mvc.perform(post("/api/v1/push/subscriptions").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(post("/api/v1/push/subscriptions").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 test").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mvc.perform(post("/api/v1/push/subscriptions").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 test").content(BODY.replace("BNcRd", "NEWKEY")))
                .andExpect(status().isOk());

        assertThat(subscriptions.findAll()).hasSize(1);
        var row = subscriptions.findByEndpoint("https://push.example/api/1").orElseThrow();
        assertThat(row.getUserId()).isEqualTo(U1);
        assertThat(row.getP256dh()).isEqualTo("NEWKEY");
        assertThat(row.getUserAgent()).isEqualTo("Mozilla/5.0 test");

        // 검증 실패: keys 누락(400 INVALID_INPUT), http endpoint(400 PUSH_SUBSCRIPTION_INVALID)
        mvc.perform(post("/api/v1/push/subscriptions").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example/x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/push/subscriptions").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("https://", "http://")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PUSH_SUBSCRIPTION_INVALID"));
    }

    @Test
    @DisplayName("DELETE /push/subscriptions: 본인 구독만 해지하고 그 구독의 미전송 배달을 CANCELLED 로, 남의 구독·없는 endpoint 는 OK 로 무시(멱등). 다음 사이클은 남은 구독에만 보낸다")
    void unsubscribe() throws Exception {
        String mine = "https://push.example/unsub/u1";
        String theirs = "https://push.example/unsub/u2";
        Event event = eventWithEnd("해지");
        subscribe(U1, mine);
        subscribe(U2, theirs);
        plan(U1, event.getId());
        plan(U2, event.getId());
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        // 첫 사이클은 503 -> 두 배달 모두 RETRY 로 대기
        doReturn(UNAVAILABLE).when(sender).send(any(), any());
        dispatcher.runCycle(NOW);
        assertThat(deliveriesOf(job)).extracting(PushDelivery::getStatus).containsOnly(PushDeliveryStatus.RETRY);

        mvc.perform(delete("/api/v1/push/subscriptions").contentType(MediaType.APPLICATION_JSON).content(endpointBody(mine)))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/push/subscriptions").with(user(U1)).contentType(MediaType.APPLICATION_JSON).content("{\"endpoint\":\" \"}"))
                .andExpect(status().isBadRequest());

        // 남의 구독: OK 지만 아무것도 바뀌지 않는다
        mvc.perform(delete("/api/v1/push/subscriptions").with(user(U1)).contentType(MediaType.APPLICATION_JSON).content(endpointBody(theirs)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        assertThat(subscriptions.findByEndpoint(theirs).orElseThrow().isDeleted()).isFalse();

        // 본인 구독: 소프트 삭제 + 그 구독의 대기 배달만 취소. 두 번째 호출과 없는 endpoint 도 OK
        for (String endpoint : new String[] {mine, mine, "https://push.example/unsub/none"}) {
            mvc.perform(delete("/api/v1/push/subscriptions").with(user(U1)).contentType(MediaType.APPLICATION_JSON).content(endpointBody(endpoint)))
                    .andExpect(status().isOk());
        }
        var mineRow = subscriptions.findByEndpoint(mine).orElseThrow();
        var theirsRow = subscriptions.findByEndpoint(theirs).orElseThrow();
        assertThat(mineRow.isDeleted()).isTrue();
        Map<Long, PushDeliveryStatus> bySubscription = deliveriesOf(job).stream()
                .collect(Collectors.toMap(PushDelivery::getSubscriptionId, PushDelivery::getStatus));
        assertThat(bySubscription).containsEntry(mineRow.getId(), PushDeliveryStatus.CANCELLED)
                .containsEntry(theirsRow.getId(), PushDeliveryStatus.RETRY);

        // 백오프가 지난 사이클: 남은 구독으로만 보내고, 열린 배달이 없으니 잡은 DONE
        doReturn(OK).when(sender).send(any(), any());
        dispatcher.runCycle(NOW.plusSeconds(3600));
        // 해지한 구독은 첫 사이클의 1회(503) 뒤로 더 보내지 않는다
        verify(sender, times(1)).send(argThat(d -> d.endpoint().equals(mine)), any());
        verify(sender, times(2)).send(argThat(d -> d.endpoint().equals(theirs)), any());
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(PushJobStatus.DONE);

        // 다시 구독하면 같은 행이 되살아난다
        mvc.perform(post("/api/v1/push/subscriptions").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("https://push.example/api/1", mine)))
                .andExpect(status().isOk());
        assertThat(subscriptions.findByEndpoint(mine).orElseThrow().isDeleted()).isFalse();
        assertThat(subscriptions.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("같은 endpoint 를 다른 사용자가 등록하면 이전 사용자 앞으로 대기 중인 배달을 취소한다 (이전 사용자 알림이 새 사용자 기기로 가지 않는다)")
    void subscribe_by_other_user_cancels_previous_owner_deliveries() throws Exception {
        String shared = "https://push.example/shared-device";
        Event event = eventWithEnd("기기 공유");
        subscribe(U1, shared);
        plan(U1, event.getId());
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        doReturn(UNAVAILABLE).when(sender).send(any(), any());
        dispatcher.runCycle(NOW);
        assertThat(deliveriesOf(job)).extracting(PushDelivery::getStatus).containsExactly(PushDeliveryStatus.RETRY);

        mvc.perform(post("/api/v1/push/subscriptions").with(user(U2)).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("https://push.example/api/1", shared)))
                .andExpect(status().isOk());

        assertThat(subscriptions.findByEndpoint(shared).orElseThrow().getUserId()).isEqualTo(U2);
        assertThat(deliveriesOf(job)).extracting(PushDelivery::getStatus).containsExactly(PushDeliveryStatus.CANCELLED);
        doReturn(OK).when(sender).send(any(), any());
        dispatcher.runCycle(NOW.plusSeconds(3600));
        verify(sender, times(1)).send(any(), any());
    }

    @Test
    @DisplayName("관리자 API: 비관리자 403, 잡 목록(status 필터)·상세(배달 포함)·수동 재시도(409 규칙)·즉시 디스패치")
    void admin_api() throws Exception {
        Event event = eventWithEnd("관리자");
        subscribe(U1, "https://push.example/admin/1");
        plan(U1, event.getId());
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        PushJob job = requireJob(event.getId(), PushKind.RECORD);

        mvc.perform(get("/api/v1/admin/push-jobs").with(user(U1))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/push-jobs").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(job.getId()))
                .andExpect(jsonPath("$.data[0].kind").value("RECORD"))
                .andExpect(jsonPath("$.data[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data[0].dueAt").value("2026-10-23T14:10:00Z"))
                .andExpect(jsonPath("$.data[0].deliveries").doesNotExist());
        mvc.perform(get("/api/v1/admin/push-jobs").param("status", "DONE").with(admin()))
                .andExpect(jsonPath("$.data.length()").value(0));

        // 즉시 디스패치: 전송은 503 -> RETRY 로 남는다. 시각은 실제 now 라 due 가 아니므로 due_at 을 당긴다
        jdbc.update("UPDATE push_jobs SET due_at = '2020-01-01 00:00:00' WHERE id = ?", job.getId());
        doReturn(UNAVAILABLE).when(sender).send(any(), any());
        mvc.perform(post("/api/v1/admin/push/dispatch").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claimed").value(1))
                .andExpect(jsonPath("$.data.materialized").value(1))
                .andExpect(jsonPath("$.data.sent").value(0));
        long deliveryId = deliveriesOf(job).get(0).getId();
        mvc.perform(get("/api/v1/admin/push-jobs/{id}", job.getId()).with(admin()))
                .andExpect(jsonPath("$.data.status").value("DISPATCHING"))
                .andExpect(jsonPath("$.data.materializedAt").exists())
                .andExpect(jsonPath("$.data.deliveries.length()").value(1))
                .andExpect(jsonPath("$.data.deliveries[0].id").value(deliveryId))
                .andExpect(jsonPath("$.data.deliveries[0].status").value("RETRY"))
                .andExpect(jsonPath("$.data.deliveries[0].attempts").value(1))
                .andExpect(jsonPath("$.data.deliveries[0].lastError").value("HTTP 503"))
                .andExpect(jsonPath("$.data.deliveries[0].nextAttemptAt").exists());
        mvc.perform(get("/api/v1/admin/push-jobs/{id}", 987654L).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUSH_JOB_NOT_FOUND"));

        // RETRY 는 수동 재시도 불가(409), FAILED 는 가능
        mvc.perform(post("/api/v1/admin/push-deliveries/{id}/retry", deliveryId).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUSH_DELIVERY_NOT_RETRYABLE"));
        jdbc.update("UPDATE push_deliveries SET status = 'FAILED', attempts = 3 WHERE id = ?", deliveryId);
        mvc.perform(post("/api/v1/admin/push-deliveries/{id}/retry", deliveryId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RETRY"))
                .andExpect(jsonPath("$.data.attempts").value(3));
        mvc.perform(post("/api/v1/admin/push-deliveries/{id}/retry", 987654L).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUSH_DELIVERY_NOT_FOUND"));
    }

    private static String endpointBody(String endpoint) {
        return "{\"endpoint\":\"" + endpoint + "\"}";
    }
}
