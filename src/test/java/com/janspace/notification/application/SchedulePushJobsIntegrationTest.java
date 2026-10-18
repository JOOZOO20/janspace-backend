package com.janspace.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.setlist.application.event.SetlistMerged;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.EventStatus;
import com.janspace.notification.application.port.PushSenderPort.PushSubscriptionData;
import com.janspace.notification.domain.PushDeliveryStatus;
import com.janspace.notification.domain.PushJob;
import com.janspace.notification.domain.PushJobStatus;
import com.janspace.notification.domain.PushKind;
import com.janspace.notification.support.NotificationIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 예약 P3·P4·P5·P11·P12·P14. 도메인 이벤트는 직접 발행한다 (리스너는 트랜잭션 밖 발행도 즉시 처리).
 */
class SchedulePushJobsIntegrationTest extends NotificationIntegrationTest {

    static final long U1 = 21L;
    static final long U2 = 22L;

    @Autowired
    SubscriptionCommand subscriptionCommand;

    @BeforeEach
    void users() {
        insertUser(U1, SEOUL);
        insertUser(U2, SEOUL);
    }

    @Test
    @DisplayName("P3 EventScheduleChanged -> RECORD 잡 1건(종료+10분). 시각을 바꿔 다시 발행하면 같은 id 에 due_at 만 바뀐다")
    void p3_record_job_rescheduled_in_place() {
        Event event = eventWithEnd("방콕");
        publisher.publishEvent(new EventScheduleChanged(event.getId()));

        PushJob first = requireJob(event.getId(), PushKind.RECORD);
        assertThat(first.getStatus()).isEqualTo(PushJobStatus.SCHEDULED);
        assertThat(first.getDueAt()).isEqualTo(Instant.parse("2026-10-23T14:10:00Z"));
        assertThat(jobs.findAllByEventId(event.getId())).hasSize(1);

        event.applySource(EventLockableField.ENDS_AT, DATE.atTime(22, 30));
        eventRepository.save(event);
        publisher.publishEvent(new EventScheduleChanged(event.getId()));

        PushJob second = requireJob(event.getId(), PushKind.RECORD);
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getDueAt()).isEqualTo(Instant.parse("2026-10-23T15:40:00Z"));
        assertThat(jobs.findAllByEventId(event.getId())).hasSize(1);
    }

    @Test
    @DisplayName("P4 종료 없음 -> FALLBACK(현지 10:00). 종료 입력 -> FALLBACK CANCELLED + RECORD. 종료 제거 -> RECORD CANCELLED + FALLBACK 되살림")
    void p4_exclusive_kinds_switch() {
        Event event = newEvent("타이베이", DATE);
        event.applySource(EventLockableField.TIMEZONE, "Asia/Taipei");
        eventRepository.save(event);
        publisher.publishEvent(new EventScheduleChanged(event.getId()));

        PushJob fallback = requireJob(event.getId(), PushKind.RECORD_FALLBACK);
        assertThat(fallback.getDueAt()).isEqualTo(Instant.parse("2026-10-24T02:00:00Z"));
        assertThat(job(event.getId(), PushKind.RECORD)).isEmpty();

        event.applySource(EventLockableField.ENDS_AT, DATE.atTime(21, 0));
        eventRepository.save(event);
        publisher.publishEvent(new EventScheduleChanged(event.getId()));

        assertThat(requireJob(event.getId(), PushKind.RECORD_FALLBACK).getStatus()).isEqualTo(PushJobStatus.CANCELLED);
        PushJob record = requireJob(event.getId(), PushKind.RECORD);
        assertThat(record.getStatus()).isEqualTo(PushJobStatus.SCHEDULED);
        assertThat(record.getDueAt()).isEqualTo(Instant.parse("2026-10-23T13:10:00Z"));

        event.applySource(EventLockableField.ENDS_AT, null);
        eventRepository.save(event);
        publisher.publishEvent(new EventScheduleChanged(event.getId()));

        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.CANCELLED);
        PushJob revived = requireJob(event.getId(), PushKind.RECORD_FALLBACK);
        assertThat(revived.getId()).isEqualTo(fallback.getId());
        assertThat(revived.getStatus()).isEqualTo(PushJobStatus.SCHEDULED);
        assertThat(jobs.findAllByEventId(event.getId())).hasSize(2);
    }

    @Test
    @DisplayName("P4 FALLBACK 이 이미 DONE 인 뒤 종료 시각이 들어오면 RECORD 잡을 만들지 않는다 (사용자는 이미 받았다)")
    void p4_no_record_after_fallback_done() {
        Event event = newEvent("발송 끝난 행사", DATE);
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        PushJob fallback = requireJob(event.getId(), PushKind.RECORD_FALLBACK);
        dispatcher.runCycle(NOW);
        assertThat(requireJob(event.getId(), PushKind.RECORD_FALLBACK).getStatus()).isEqualTo(PushJobStatus.DONE);

        event.applySource(EventLockableField.ENDS_AT, DATE.atTime(21, 0));
        eventRepository.save(event);
        publisher.publishEvent(new EventScheduleChanged(event.getId()));

        assertThat(job(event.getId(), PushKind.RECORD)).isEmpty();
        assertThat(requireJob(event.getId(), PushKind.RECORD_FALLBACK).getId()).isEqualTo(fallback.getId());
        assertThat(jobs.findAllByEventId(event.getId())).hasSize(1);
    }

    @Test
    @DisplayName("P5 DONE 잡은 시각이 바뀌어도 재예약되지 않는다")
    void p5_done_job_never_rescheduled() {
        Event event = eventWithEnd("끝난 공연");
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        dispatcher.runCycle(NOW);
        PushJob done = requireJob(event.getId(), PushKind.RECORD);
        assertThat(done.getStatus()).isEqualTo(PushJobStatus.DONE);

        event.applySource(EventLockableField.ENDS_AT, DATE.atTime(23, 0));
        eventRepository.save(event);
        publisher.publishEvent(new EventScheduleChanged(event.getId()));

        PushJob after = requireJob(event.getId(), PushKind.RECORD);
        assertThat(after.getStatus()).isEqualTo(PushJobStatus.DONE);
        assertThat(after.getDueAt()).isEqualTo(done.getDueAt());
    }

    @Test
    @DisplayName("P11 due 전에 행사가 CANCELLED 되면 SCHEDULED 잡은 CANCELLED, 0건 발송 (이벤트 발행 경로와 디스패처 감지 경로 모두)")
    void p11_cancelled_event_cancels_scheduled_job() {
        Event byEvent = eventWithEnd("이벤트로 취소");
        Event byDispatcher = eventWithEnd("디스패처가 감지");
        subscribe(U1, "https://push.example/u1");
        plan(U1, byEvent.getId());
        plan(U1, byDispatcher.getId());
        publisher.publishEvent(new EventScheduleChanged(byEvent.getId()));
        publisher.publishEvent(new EventScheduleChanged(byDispatcher.getId()));

        byEvent.changeStatus(EventStatus.CANCELLED);
        eventRepository.save(byEvent);
        publisher.publishEvent(new EventScheduleChanged(byEvent.getId()));
        assertThat(requireJob(byEvent.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.CANCELLED);

        byDispatcher.changeStatus(EventStatus.CANCELLED);
        eventRepository.save(byDispatcher);
        var result = dispatcher.runCycle(NOW);

        assertThat(result.cancelled()).isEqualTo(1);
        assertThat(result.sent()).isZero();
        assertThat(requireJob(byDispatcher.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.CANCELLED);
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("P11 DISPATCHING 중 취소: 미전송(RETRY) 배달만 CANCELLED, SENT 는 그대로. 다음 사이클에 잡 DONE")
    void p11_cancel_during_dispatching_keeps_sent() {
        Event event = eventWithEnd("발송 중 취소");
        subscribe(U1, "https://push.example/ok");
        subscribe(U2, "https://push.example/down");
        plan(U1, event.getId());
        plan(U2, event.getId());
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        when(sender.send(any(), any())).thenAnswer(inv -> inv.getArgument(0, PushSubscriptionData.class).endpoint().endsWith("/ok") ? OK : UNAVAILABLE);
        dispatcher.runCycle(NOW);
        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        assertThat(job.getStatus()).isEqualTo(PushJobStatus.DISPATCHING);
        assertThat(deliveriesOf(job)).extracting(d -> d.getStatus()).containsExactlyInAnyOrder(PushDeliveryStatus.SENT, PushDeliveryStatus.RETRY);

        event.changeStatus(EventStatus.CANCELLED);
        eventRepository.save(event);
        publisher.publishEvent(new EventScheduleChanged(event.getId()));

        assertThat(deliveriesOf(job)).extracting(d -> d.getStatus()).containsExactlyInAnyOrder(PushDeliveryStatus.SENT, PushDeliveryStatus.CANCELLED);
        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DISPATCHING);
        dispatcher.runCycle(NOW.plusSeconds(600));
        assertThat(requireJob(event.getId(), PushKind.RECORD).getStatus()).isEqualTo(PushJobStatus.DONE);
        assertThat(deliveriesOf(job)).extracting(d -> d.getStatus()).containsExactlyInAnyOrder(PushDeliveryStatus.SENT, PushDeliveryStatus.CANCELLED);
    }

    @Test
    @DisplayName("P12 UserWithdrawn -> 그 사용자의 구독 삭제 + 미전송 배달 취소. 다른 사용자는 그대로")
    void p12_user_withdrawn_deletes_subscriptions() {
        Event event = eventWithEnd("탈퇴");
        subscribe(U1, "https://push.example/u1-a");
        subscribe(U1, "https://push.example/u1-b");
        subscribe(U2, "https://push.example/u2");
        plan(U1, event.getId());
        plan(U2, event.getId());
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        when(sender.send(any(), any())).thenReturn(UNAVAILABLE);
        dispatcher.runCycle(NOW);
        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        assertThat(deliveriesOf(job)).hasSize(3);

        publisher.publishEvent(new UserWithdrawn(U1));

        assertThat(subscriptions.findAllByUserIdAndDeletedAtIsNull(U1)).isEmpty();
        assertThat(subscriptions.findByEndpoint("https://push.example/u1-a").orElseThrow().isDeleted()).isTrue();
        assertThat(subscriptions.findAllByUserIdAndDeletedAtIsNull(U2)).hasSize(1);
        assertThat(deliveriesOf(job)).filteredOn(d -> d.getUserId().equals(U1)).extracting(d -> d.getStatus())
                .containsOnly(PushDeliveryStatus.CANCELLED);
        assertThat(deliveriesOf(job)).filteredOn(d -> d.getUserId().equals(U2)).extracting(d -> d.getStatus())
                .containsOnly(PushDeliveryStatus.RETRY);
    }

    @Test
    @DisplayName("P14 같은 endpoint 로 두 번 구독하면 한 행이고 키·사용자가 갱신된다. 삭제된 구독도 되살아난다")
    void p14_subscribe_upserts_by_endpoint() {
        String endpoint = "https://push.example/same";
        var first = subscriptionCommand.subscribe(U1, endpoint, "k1", "a1", "ua");
        var second = subscriptionCommand.subscribe(U2, endpoint, "k2", "a2", null);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(subscriptions.findAll()).hasSize(1);
        var row = subscriptions.findByEndpoint(endpoint).orElseThrow();
        assertThat(row.getUserId()).isEqualTo(U2);
        assertThat(row.getP256dh()).isEqualTo("k2");
        assertThat(row.getAuth()).isEqualTo("a2");

        publisher.publishEvent(new UserWithdrawn(U2));
        assertThat(subscriptions.findByEndpoint(endpoint).orElseThrow().isDeleted()).isTrue();
        subscriptionCommand.subscribe(U1, endpoint, "k3", "a3", null);
        var revived = subscriptions.findByEndpoint(endpoint).orElseThrow();
        assertThat(revived.isDeleted()).isFalse();
        assertThat(revived.getUserId()).isEqualTo(U1);
        assertThat(subscriptions.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("onSetlistMerged 는 SETLIST_CONFIRMED 잡을 지금 시각으로 만들고, 이미 있으면 재예약만 한다")
    void setlist_merged_upserts() {
        Event event = eventWithEnd("셋리");
        Instant before = Instant.now();
        schedulePushJobs.onSetlistMerged(event.getId());
        PushJob job = requireJob(event.getId(), PushKind.SETLIST_CONFIRMED);
        assertThat(job.getDueAt()).isBetween(before, Instant.now());
        schedulePushJobs.onSetlistMerged(event.getId());
        assertThat(jobs.findAllByEventId(event.getId())).extracting(PushJob::getKind).containsExactly(PushKind.SETLIST_CONFIRMED);
        assertThat(requireJob(event.getId(), PushKind.SETLIST_CONFIRMED).getId()).isEqualTo(job.getId());
    }

    @Test
    @DisplayName("setlist 의 SetlistMerged 이벤트가 발행되면 SETLIST_CONFIRMED 잡이 예약된다")
    void setlist_merged_event_schedules_job() {
        Event event = eventWithEnd("셋리 이벤트");
        publisher.publishEvent(new SetlistMerged(event.getId()));
        assertThat(requireJob(event.getId(), PushKind.SETLIST_CONFIRMED).getStatus().name()).isEqualTo("SCHEDULED");
    }

}
