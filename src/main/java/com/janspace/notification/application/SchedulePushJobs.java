package com.janspace.notification.application;

import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import com.janspace.event.application.RunningTime;
import com.janspace.notification.domain.PushDeliveryRepository;
import com.janspace.notification.domain.PushJob;
import com.janspace.notification.domain.PushJobPlanner;
import com.janspace.notification.domain.PushJobPlanner.Plan;
import com.janspace.notification.domain.PushJobRepository;
import com.janspace.notification.domain.PushJobStatus;
import com.janspace.notification.domain.PushKind;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 잡 예약. 행사 일정 변경 -> RECORD/RECORD_FALLBACK 배타 upsert, 셋리 병합 -> SETLIST_CONFIRMED, 행사 취소·삭제 -> 취소.
 * DISPATCHING/DONE 잡은 어떤 경우에도 재예약하지 않는다. 예약 시각 규칙은 PushJobPlanner, 여유·기본 공연 시간·당일 시각은 janspace.push 설정.
 */
@Slf4j
@Service
@Transactional
public class SchedulePushJobs {

    private final PushJobRepository jobs;
    private final PushDeliveryRepository deliveries;
    private final EventQuery eventQuery;
    private final PushJobPlanner planner;
    private final Duration concertDefaultDuration;
    private final Duration otherDefaultDuration;
    private final TransactionTemplate tx;

    public SchedulePushJobs(PushJobRepository jobs, PushDeliveryRepository deliveries, EventQuery eventQuery, TransactionTemplate tx,
                            @Value("${janspace.push.official-buffer:10m}") Duration officialBuffer,
                            @Value("${janspace.push.estimated-buffer:20m}") Duration estimatedBuffer,
                            @Value("${janspace.push.concert-default-duration:150m}") Duration concertDefaultDuration,
                            @Value("${janspace.push.other-default-duration:60m}") Duration otherDefaultDuration,
                            @Value("${janspace.push.date-only-local-time:22:00}") String dateOnlyLocalTime) {
        this.jobs = jobs;
        this.deliveries = deliveries;
        this.eventQuery = eventQuery;
        this.planner = new PushJobPlanner(officialBuffer, estimatedBuffer, LocalTime.parse(dateOnlyLocalTime));
        this.concertDefaultDuration = concertDefaultDuration;
        this.otherDefaultDuration = otherDefaultDuration;
        this.tx = tx;
    }

    /**
     * 보정: 어제(현지 날짜가 서울보다 늦은 공연장) 이후 행사의 기록 알림을 다시 예약한다. EventScheduleChanged 리스너가 실패해 빠진 예약을 메운다.
     * upsert 라 몇 번 돌려도 같고, 발송이 시작된 잡은 건드리지 않는다. 행사마다 트랜잭션을 나눠 한 행사의 실패가 나머지를 막지 않는다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int resyncUpcoming(LocalDate today) {
        int failed = 0;
        List<EventSnapshot> upcoming = eventQuery.findBetween(today.minusDays(1), today.plusYears(10));
        for (EventSnapshot event : upcoming) {
            try {
                tx.executeWithoutResult(status -> onEventScheduleChanged(event.id()));
            } catch (RuntimeException e) {
                failed++;
                log.error("[푸시 재예약 보정 실패] eventId={}: 다음 보정에서 다시 시도", event.id(), e);
            }
        }
        log.info("[푸시 재예약 보정] events={}, failed={}", upcoming.size(), failed);
        return upcoming.size() - failed;
    }

    public void onEventScheduleChanged(Long eventId) {
        // 1. 취소·삭제된 행사는 예약 대신 취소
        Optional<EventSnapshot> found = eventQuery.find(eventId);
        if (found.isEmpty() || found.get().status() == EventSnapshot.Status.CANCELLED) {
            cancelForEvent(eventId);
            return;
        }
        EventSnapshot event = found.get();
        RunningTime runningTime = eventQuery.runningTime(eventId, concertDefaultDuration, otherDefaultDuration).orElseThrow();
        Plan plan = planner.plan(event.endsAt(), event.startsAt(), runningTime.duration(),
                runningTime.basis() == RunningTime.Basis.OFFICIAL, event.eventDate(), event.timezone());

        // 2. 배타 종류 처리: SCHEDULED 면 취소, 이미 발송 시작(DISPATCHING/DONE)이면 새 잡을 만들지 않는다 (사용자는 이미 받았다)
        Optional<PushJob> other = jobs.findByEventIdAndKind(eventId, plan.kind().exclusiveCounterpart());
        if (other.isPresent() && other.get().isDispatchStarted()) {
            log.info("[푸시 예약 건너뜀 - 배타 잡 발송 시작됨] eventId={}, kind={}, otherKind={}, otherStatus={}", eventId, plan.kind(),
                    other.get().getKind(), other.get().getStatus());
            return;
        }
        other.ifPresent(PushJob::cancel);

        // 3. 계획된 종류 upsert. SCHEDULED/CANCELLED 는 due_at 갱신(되살림 포함), 발송 시작된 잡은 그대로
        upsert(eventId, plan.kind(), plan.dueAt());
    }

    // 셋리 병합 완료 (PushEventListeners 가 SetlistMerged 를 잇는다)
    public void onSetlistMerged(Long eventId) {
        upsert(eventId, PushKind.SETLIST_CONFIRMED, Instant.now());
    }

    // 행사 취소·삭제: SCHEDULED 잡 취소, DISPATCHING 잡은 미전송 배달만 취소 (SENT 는 그대로)
    public void cancelForEvent(Long eventId) {
        List<PushJob> all = jobs.findAllByEventId(eventId);
        long cancelledJobs = all.stream().filter(PushJob::cancel).count();
        List<Long> dispatching = all.stream().filter(j -> j.getStatus() == PushJobStatus.DISPATCHING).map(PushJob::getId).toList();
        int cancelledDeliveries = deliveries.cancelUnsentByJobIds(dispatching);
        log.info("[푸시 잡 취소] eventId={}, cancelledJobs={}, cancelledDeliveries={}", eventId, cancelledJobs, cancelledDeliveries);
    }

    private void upsert(Long eventId, PushKind kind, Instant dueAt) {
        Optional<PushJob> existing = jobs.findByEventIdAndKind(eventId, kind);
        if (existing.isEmpty()) {
            PushJob created = jobs.save(PushJob.schedule(eventId, kind, dueAt));
            log.info("[푸시 잡 생성] jobId={}, eventId={}, kind={}, dueAt={}", created.getId(), eventId, kind, dueAt);
            return;
        }
        PushJob job = existing.get();
        if (job.reschedule(dueAt)) {
            log.info("[푸시 잡 재예약] jobId={}, eventId={}, kind={}, dueAt={}", job.getId(), eventId, kind, dueAt);
        } else {
            log.info("[푸시 잡 재예약 건너뜀] jobId={}, status={}: 발송이 이미 시작됨", job.getId(), job.getStatus());
        }
    }
}
