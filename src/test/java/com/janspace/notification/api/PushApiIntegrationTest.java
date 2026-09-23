package com.janspace.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.domain.Event;
import com.janspace.notification.domain.PushJob;
import com.janspace.notification.domain.PushKind;
import com.janspace.notification.support.NotificationIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 공개 API(구독 등록·VAPID 공개키)와 관리자 API(잡 목록·상세·수동 재시도·즉시 디스패치) + 권한.
 */
class PushApiIntegrationTest extends NotificationIntegrationTest {

    static final long U1 = 41L;
    static final String BODY = "{\"endpoint\":\"https://push.example/api/1\",\"expirationTime\":null,"
            + "\"keys\":{\"p256dh\":\"BNcRd\",\"auth\":\"tBHI\"}}";

    @BeforeEach
    void users() {
        insertUser(U1, SEOUL);
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
}
