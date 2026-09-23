package com.janspace.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.domain.Event;
import com.janspace.notification.application.DispatchDuePushJobs.CycleResult;
import com.janspace.notification.application.port.PushSenderPort.PushSubscriptionData;
import com.janspace.notification.application.port.PushSenderPort.SendResult;
import com.janspace.notification.domain.PushDelivery;
import com.janspace.notification.domain.PushDeliveryStatus;
import com.janspace.notification.domain.PushJob;
import com.janspace.notification.domain.PushJobStatus;
import com.janspace.notification.domain.PushKind;
import com.janspace.notification.support.NotificationIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 디스패처 P6·P7·P8·P9·P10 + 고아 회수·0건 잡·물질화 전 DONE 제외.
 */
class DispatchDuePushJobsIntegrationTest extends NotificationIntegrationTest {

    static final long U1 = 31L;
    static final long U2 = 32L;
    static final long U3 = 33L;

    @BeforeEach
    void users() {
        insertUser(U1, SEOUL);
        insertUser(U2, SEOUL);
        insertUser(U3, SEOUL);
        doReturn(OK).when(sender).send(any(), any());
    }

    @Test
    @DisplayName("P6 잡 3건 중 due 2건, 사용자 2명 x 구독 2개 -> 8건 전송, 두 잡 DONE. 두 번째 사이클은 0건")
    void p6_sends_once_per_subscription_and_completes() {
        Event dueA = eventWithEnd("A");
        Event dueB = eventWithEnd("B");
        Event future = eventWithEnd("미래", DATE.plusYears(1), DATE.plusYears(1).atTime(21, 0), "Asia/Seoul");
        for (long user : List.of(U1, U2)) {
            subscribe(user, "https://push.example/" + user + "/1");
            subscribe(user, "https://push.example/" + user + "/2");
            plan(user, dueA.getId());
            plan(user, dueB.getId());
            plan(user, future.getId());
        }
        List.of(dueA, dueB, future).forEach(e -> publisher.publishEvent(new EventScheduleChanged(e.getId())));

        CycleResult first = dispatcher.runCycle(NOW);

        assertThat(first.claimed()).isEqualTo(2);
        assertThat(first.materialized()).isEqualTo(2);
        assertThat(first.sent()).isEqualTo(8);
        assertThat(first.completed()).isEqualTo(2);
        verify(sender, times(8)).send(any(), any());
        assertThat(requireJob(dueA.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DONE);
        assertThat(requireJob(dueB.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DONE);
        assertThat(requireJob(future.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.SCHEDULED);
        assertThat(deliveriesOf(requireJob(dueA.getId(), PushKind.RECORD))).hasSize(4)
                .allSatisfy(d -> {
                    assertThat(d.getStatus()).isEqualTo(PushDeliveryStatus.SENT);
                    assertThat(d.getAttempts()).isEqualTo(1);
                    assertThat(d.getSentAt()).isEqualTo(NOW);
                });

        CycleResult second = dispatcher.runCycle(NOW.plusSeconds(60));
        assertThat(second).isEqualTo(new CycleResult(0, 0, 0, 0, 0, 0));
        verify(sender, times(8)).send(any(), any());
    }

    @Test
    @DisplayName("P7 두 디스패처가 같은 due 잡을 동시에 돌려도 물질화 1회, 배달 집합 1개, 구독당 정확히 1회 전송")
    void p7_concurrent_cycles_do_not_duplicate() throws Exception {
        Event event = eventWithEnd("경합");
        for (long user : List.of(U1, U2, U3)) {
            subscribe(user, "https://push.example/race/" + user + "/1");
            subscribe(user, "https://push.example/race/" + user + "/2");
            plan(user, event.getId());
        }
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        Map<String, AtomicInteger> sendsPerEndpoint = new ConcurrentHashMap<>();
        // 전송을 늦춰 두 사이클의 선점·전송 구간이 겹치게 한다
        doAnswer(inv -> {
            sendsPerEndpoint.computeIfAbsent(inv.getArgument(0, PushSubscriptionData.class).endpoint(), k -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(150);
            return OK;
        }).when(sender).send(any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CycleResult> a = pool.submit(() -> dispatcher.runCycle(NOW));
            Future<CycleResult> b = pool.submit(() -> dispatcher.runCycle(NOW));
            CycleResult ra = a.get(30, TimeUnit.SECONDS);
            CycleResult rb = b.get(30, TimeUnit.SECONDS);
            assertThat(ra.claimed() + rb.claimed()).isEqualTo(1);
            assertThat(ra.materialized() + rb.materialized()).isEqualTo(1);
            assertThat(ra.sent() + rb.sent()).isEqualTo(6);
        } finally {
            pool.shutdownNow();
        }

        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        assertThat(sendsPerEndpoint).hasSize(6);
        assertThat(sendsPerEndpoint.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        assertThat(deliveriesOf(job)).hasSize(6).allSatisfy(d -> assertThat(d.getStatus()).isEqualTo(PushDeliveryStatus.SENT));
        assertThat(job.getMaterializedAt()).isEqualTo(NOW);
        // 완료 판정은 두 사이클 중 한쪽 또는 다음 사이클에서 한 번
        dispatcher.runCycle(NOW.plusSeconds(60));
        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DONE);
        verify(sender, times(6)).send(any(), any());
    }

    @Test
    @DisplayName("P7 물질화가 INSERT 뒤 materialized_at 확정 전에 죽으면 전부 롤백, 다음 사이클이 집합을 정확히 한 번 만든다. 물질화 전에는 DONE 이 되지 않는다")
    void p7_materialization_crash_rolls_back_and_retries() {
        Event event = eventWithEnd("중단");
        subscribe(U1, "https://push.example/crash/1");
        subscribe(U2, "https://push.example/crash/2");
        plan(U1, event.getId());
        plan(U2, event.getId());
        Event empty = eventWithEnd("대상 없음");
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        publisher.publishEvent(new EventScheduleChanged(empty.getId()));
        doThrow(new IllegalStateException("simulated crash after insert")).when(jobs).markMaterialized(any(), any());

        CycleResult crashed = dispatcher.runCycle(NOW);

        assertThat(crashed.claimed()).isEqualTo(2);
        assertThat(crashed.materialized()).isZero();
        assertThat(crashed.completed()).as("물질화 전 잡은 배달 0건이어도 DONE 이 아니다").isZero();
        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        assertThat(job.getStatus()).isEqualTo(PushJobStatus.DISPATCHING);
        assertThat(job.getMaterializedAt()).isNull();
        assertThat(deliveriesOf(job)).isEmpty();
        assertThat(requireJob(empty.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DISPATCHING);
        verify(sender, times(0)).send(any(), any());

        reset(jobs);
        CycleResult recovered = dispatcher.runCycle(NOW.plusSeconds(60));

        assertThat(recovered.materialized()).isEqualTo(2);
        assertThat(recovered.sent()).isEqualTo(2);
        assertThat(deliveriesOf(job)).hasSize(2);
        assertThat(requireJob(event.getId(), PushKind.RECORD).getMaterializedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DONE);
        assertThat(requireJob(empty.getId(), PushKind.RECORD).getStatus()).as("배달 0건은 물질화 즉시 DONE").isEqualTo(PushJobStatus.DONE);
        // reset 이후 호출만 센다: 두 잡 각각 정확히 한 번 확정
        verify(jobs, times(2)).markMaterialized(any(), any());
    }

    @Test
    @DisplayName("P8 410 -> GONE + 구독 소프트 삭제. 503 -> RETRY(next_attempt_at 백오프), 3회째 FAILED -> 잡 DONE. 수동 재시도로 다시 보낸다")
    void p8_outcomes_and_manual_retry() {
        Event event = eventWithEnd("결과");
        subscribe(U1, "https://push.example/gone");
        subscribe(U2, "https://push.example/down");
        plan(U1, event.getId());
        plan(U2, event.getId());
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        doAnswer(inv -> inv.getArgument(0, PushSubscriptionData.class).endpoint().endsWith("/gone")
                ? new SendResult(410, "HTTP 410") : UNAVAILABLE).when(sender).send(any(), any());

        dispatcher.runCycle(NOW);
        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        PushDelivery gone = deliveryOf(job, U1);
        PushDelivery down = deliveryOf(job, U2);
        assertThat(gone.getStatus()).isEqualTo(PushDeliveryStatus.GONE);
        assertThat(subscriptions.findByEndpoint("https://push.example/gone").orElseThrow().isDeleted()).isTrue();
        assertThat(down.getStatus()).isEqualTo(PushDeliveryStatus.RETRY);
        assertThat(down.getAttempts()).isEqualTo(1);
        assertThat(down.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(down.getLastError()).isEqualTo("HTTP 503");
        assertThat(job.getStatus()).isEqualTo(PushJobStatus.DISPATCHING);

        // 아직 재시도 시각 전이면 보내지 않는다
        assertThat(dispatcher.runCycle(NOW.plusSeconds(30)).sent()).isZero();
        verify(sender, times(2)).send(any(), any());

        dispatcher.runCycle(NOW.plus(Duration.ofMinutes(1)));
        down = deliveryOf(job, U2);
        assertThat(down.getAttempts()).isEqualTo(2);
        assertThat(down.getStatus()).isEqualTo(PushDeliveryStatus.RETRY);
        assertThat(down.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(6)));

        dispatcher.runCycle(NOW.plus(Duration.ofMinutes(6)));
        down = deliveryOf(job, U2);
        assertThat(down.getAttempts()).isEqualTo(3);
        assertThat(down.getStatus()).isEqualTo(PushDeliveryStatus.FAILED);
        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DONE);
        verify(sender, times(4)).send(any(), any());

        // 관리자 수동 재시도: FAILED -> RETRY, 잡 DONE -> DISPATCHING, 다음 사이클에 1회 더 시도
        doReturn(OK).when(sender).send(any(), any());
        adminPushCommand.retryDelivery(down.getId());
        assertThat(deliveryOf(job, U2).getStatus()).isEqualTo(PushDeliveryStatus.RETRY);
        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DISPATCHING);
        CycleResult manual = dispatcher.runCycle(NOW.plus(Duration.ofMinutes(7)));
        assertThat(manual.sent()).isEqualTo(1);
        assertThat(deliveryOf(job, U2).getStatus()).isEqualTo(PushDeliveryStatus.SENT);
        assertThat(deliveryOf(job, U2).getAttempts()).isEqualTo(4);
        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DONE);
    }

    @Test
    @DisplayName("P9 SETLIST_CONFIRMED 대상은 최고의 곡을 고르지 않은 기록자만. 페이로드는 사용자 로케일 문구")
    void p9_setlist_confirmed_recipients_and_locale() {
        Event event = eventWithEnd("셋리 확정");
        long song = insertSong("주저하는 연인들을 위해");
        recorded(U1, event.getId(), null);
        recorded(U2, event.getId(), song);
        plan(U3, event.getId());
        subscribe(U1, "https://push.example/u1");
        subscribe(U2, "https://push.example/u2");
        subscribe(U3, "https://push.example/u3");
        jdbc.update("UPDATE users SET locale = 'ja' WHERE id = ?", U1);

        schedulePushJobs.onSetlistMerged(event.getId());
        CycleResult result = dispatcher.runCycle(NOW);

        assertThat(result.sent()).isEqualTo(1);
        ArgumentCaptor<PushSubscriptionData> target = ArgumentCaptor.forClass(PushSubscriptionData.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(sender).send(target.capture(), payload.capture());
        assertThat(target.getValue().endpoint()).isEqualTo("https://push.example/u1");
        assertThat(payload.getValue()).contains("\"kind\":\"SETLIST_CONFIRMED\"").contains("\"eventId\":" + event.getId())
                .contains("セットリスト").contains("\"url\":\"/events/" + event.getId() + "\"");
        assertThat(deliveriesOf(requireJob(event.getId(), PushKind.SETLIST_CONFIRMED))).hasSize(1);
    }

    @Test
    @DisplayName("P10 물질화 뒤에 생긴 갈래요·구독은 그 잡의 대상이 아니다")
    void p10_materialized_once() {
        Event event = eventWithEnd("확정 후 추가");
        subscribe(U1, "https://push.example/u1");
        plan(U1, event.getId());
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        doReturn(UNAVAILABLE).when(sender).send(any(), any());
        dispatcher.runCycle(NOW);
        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        assertThat(job.getStatus()).isEqualTo(PushJobStatus.DISPATCHING);
        assertThat(deliveriesOf(job)).hasSize(1);

        subscribe(U1, "https://push.example/u1-new-device");
        subscribe(U2, "https://push.example/u2");
        plan(U2, event.getId());
        doReturn(OK).when(sender).send(any(), any());
        CycleResult second = dispatcher.runCycle(NOW.plus(Duration.ofMinutes(1)));

        assertThat(second.sent()).isEqualTo(1);
        assertThat(deliveriesOf(job)).hasSize(1);
        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DONE);
    }

    @Test
    @DisplayName("고아 SENDING(5분 초과) 회수: attempts<3 이면 RETRY, 아니면 FAILED(orphaned). 5분 안이면 그대로")
    void orphan_recovery() {
        Event event = eventWithEnd("고아");
        subscribe(U1, "https://push.example/o1");
        subscribe(U2, "https://push.example/o2");
        subscribe(U3, "https://push.example/o3");
        for (long u : List.of(U1, U2, U3)) {
            plan(u, event.getId());
        }
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        doReturn(UNAVAILABLE).when(sender).send(any(), any());
        dispatcher.runCycle(NOW);
        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        Instant t = NOW.plus(Duration.ofMinutes(10));
        jdbc.update("UPDATE push_deliveries SET status = 'SENDING', sending_started_at = ?, attempts = 1 WHERE id = ?",
                utc(t.minus(Duration.ofMinutes(6))), deliveryOf(job, U1).getId());
        jdbc.update("UPDATE push_deliveries SET status = 'SENDING', sending_started_at = ?, attempts = 3 WHERE id = ?",
                utc(t.minus(Duration.ofMinutes(6))), deliveryOf(job, U2).getId());
        jdbc.update("UPDATE push_deliveries SET status = 'SENDING', sending_started_at = ?, attempts = 1 WHERE id = ?",
                utc(t.minus(Duration.ofMinutes(2))), deliveryOf(job, U3).getId());

        CycleResult result = dispatcher.runCycle(t);

        assertThat(result.recovered()).isEqualTo(2);
        assertThat(result.sent()).isZero();
        PushDelivery retried = deliveryOf(job, U1);
        assertThat(retried.getStatus()).isEqualTo(PushDeliveryStatus.RETRY);
        assertThat(retried.getLastError()).isEqualTo(PushDelivery.ORPHANED);
        assertThat(retried.getNextAttemptAt()).isEqualTo(t.plus(Duration.ofMinutes(1)));
        assertThat(deliveryOf(job, U2).getStatus()).isEqualTo(PushDeliveryStatus.FAILED);
        assertThat(deliveryOf(job, U3).getStatus()).isEqualTo(PushDeliveryStatus.SENDING);
        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DISPATCHING);
    }

    @Autowired
    AdminPushCommand adminPushCommand;

    // JdbcTemplate 은 Timestamp 를 JVM 시간대로 보내므로 UTC 벽시계(LocalDateTime)로 넘긴다
    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    PushDelivery deliveryOf(PushJob job, long userId) {
        return deliveriesOf(job).stream().filter(d -> d.getUserId().equals(userId)).findFirst().orElseThrow();
    }
}
