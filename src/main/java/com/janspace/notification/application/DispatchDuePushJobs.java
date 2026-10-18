package com.janspace.notification.application;

import com.janspace.account.application.AccountQuery;
import com.janspace.account.application.AccountSnapshot;
import com.janspace.attendance.application.AttendanceQuery;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.common.locale.LocalizedText;
import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import com.janspace.notification.application.port.PushSenderPort;
import com.janspace.notification.application.port.PushSenderPort.PushSubscriptionData;
import com.janspace.notification.application.port.PushSenderPort.SendResult;
import com.janspace.notification.domain.PushDelivery;
import com.janspace.notification.domain.PushDeliveryRepository;
import com.janspace.notification.domain.PushDeliveryStatus;
import com.janspace.notification.domain.PushJob;
import com.janspace.notification.domain.PushJobRepository;
import com.janspace.notification.domain.PushJobStatus;
import com.janspace.notification.domain.PushSubscription;
import com.janspace.notification.domain.PushSubscriptionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 1분 주기 디스패처의 한 사이클. 인스턴스가 여럿이어도 안전하도록 잡·배달 선점은 조건부 UPDATE 한 건짜리 짧은 트랜잭션으로 하고,
 * 물질화는 한 트랜잭션에서 materialized_at 확정(조건부 UPDATE, 잡 행 잠금 = 뮤텍스) 뒤 INSERT 로 한 번만 일어난다.
 * 전송(외부 호출)은 트랜잭션 밖에서 하고 결과만 짧은 트랜잭션으로 기록한다. 배달 하나의 예외는 그 배달만 실패로 기록하고 사이클을 잇는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchDuePushJobs {

    // 이 시간 넘게 SENDING 이면 프로세스가 죽은 것으로 보고 회수한다
    public static final Duration ORPHAN_AFTER = Duration.ofMinutes(5);

    public record CycleResult(int claimed, int cancelled, int materialized, int sent, int recovered, int completed) {
    }

    private final PushJobRepository jobs;
    private final PushDeliveryRepository deliveries;
    private final PushSubscriptionRepository subscriptions;
    private final AttendanceQuery attendanceQuery;
    private final EventQuery eventQuery;
    private final AccountQuery accountQuery;
    private final PushMessageRenderer renderer;
    private final PushSenderPort sender;
    private final TransactionTemplate tx;

    public CycleResult runCycle() {
        return runCycle(Instant.now());
    }

    public CycleResult runCycle(Instant now) {
        // 1. 잡 선점 (취소·삭제된 행사의 잡은 선점 대신 취소)
        int claimed = 0;
        int cancelled = 0;
        for (PushJob job : jobs.findAllByStatusAndDueAtLessThanEqual(PushJobStatus.SCHEDULED, now)) {
            if (!eventAlive(job.getEventId())) {
                cancelled += cancelJob(job);
            } else if (tx.execute(status -> jobs.claim(job.getId(), now)) == 1) {
                claimed++;
                log.info("[푸시 잡 선점] jobId={}, eventId={}, kind={}", job.getId(), job.getEventId(), job.getKind());
            }
        }
        // 2. 물질화 (잡마다 한 트랜잭션. 실패하면 다음 사이클이 처음부터 다시)
        int materialized = 0;
        for (PushJob job : jobs.findAllByStatusAndMaterializedAtIsNull(PushJobStatus.DISPATCHING)) {
            try {
                if (tx.execute(status -> materialize(job, now))) {
                    materialized++;
                }
            } catch (RuntimeException e) {
                log.error("[푸시 물질화 실패] jobId={}, eventId={}: 롤백, 다음 사이클에 재시도", job.getId(), job.getEventId(), e);
            }
        }
        // 3. 배달 선점·전송 (물질화된 잡의 저장된 배달만). VAPID 키가 없으면 보내 봐야 전부 실패이므로 PENDING 으로 둔다
        int sent = sender.keysMissing() ? 0 : sendDue(now);
        // 4. 고아 SENDING 회수
        int recovered = 0;
        for (PushDelivery orphan : deliveries.findAllByStatusAndSendingStartedAtBefore(PushDeliveryStatus.SENDING, now.minus(ORPHAN_AFTER))) {
            tx.executeWithoutResult(status -> deliveries.findById(orphan.getId())
                    .filter(d -> d.getStatus() == PushDeliveryStatus.SENDING)
                    .ifPresent(d -> log.warn("[푸시 고아 배달 회수] deliveryId={}, attempts={}, next={}", d.getId(), d.getAttempts(), d.recoverOrphan(now))));
            recovered++;
        }
        // 5. 완료 (물질화 후 열린 배달이 없는 잡만)
        int completed = 0;
        for (Long jobId : jobs.findCompletableIds()) {
            completed += tx.execute(status -> jobs.markDone(jobId));
        }
        CycleResult result = new CycleResult(claimed, cancelled, materialized, sent, recovered, completed);
        if (claimed + cancelled + materialized + sent + recovered + completed > 0) {
            log.info("[푸시 디스패치 사이클] result={}", result);
        }
        return result;
    }

    // materialized_at 을 먼저 확정하고(잡 행 잠금) 대상 사용자의 구독 전부를 PENDING 배달로 저장한다. 배달 0건이면 즉시 DONE.
    // 다른 인스턴스가 같은 잡을 물질화 중이면 UPDATE 가 그 커밋을 기다린 뒤 영향 행 0 이 되므로 조용히 빠진다
    private boolean materialize(PushJob job, Instant now) {
        if (jobs.markMaterialized(job.getId(), now) != 1) {
            log.info("[푸시 물질화 건너뜀] jobId={}: 다른 인스턴스가 먼저 물질화함", job.getId());
            return false;
        }
        List<Long> recipients = switch (job.getKind()) {
            case RECORD, RECORD_FALLBACK -> attendanceQuery.plannedUserIds(job.getEventId());
            case SETLIST_CONFIRMED -> attendanceQuery.recordedWithoutBestSongUserIds(job.getEventId());
        };
        List<PushSubscription> targets = recipients.isEmpty() ? List.of() : subscriptions.findAllByUserIdInAndDeletedAtIsNull(recipients);
        deliveries.saveAll(targets.stream().map(s -> PushDelivery.pending(job.getId(), s.getId(), s.getUserId())).toList());
        if (targets.isEmpty()) {
            jobs.markDone(job.getId());
        }
        log.info("[푸시 물질화] jobId={}, kind={}, recipients={}, deliveries={}", job.getId(), job.getKind(), recipients.size(), targets.size());
        return true;
    }

    private int sendDue(Instant now) {
        Map<Long, List<PushDelivery>> byJob = new LinkedHashMap<>();
        deliveries.findDue(now).forEach(d -> byJob.computeIfAbsent(d.getJobId(), k -> new ArrayList<>()).add(d));
        int sent = 0;
        Map<Long, SupportedLocale> locales = new HashMap<>();
        for (Map.Entry<Long, List<PushDelivery>> entry : byJob.entrySet()) {
            PushJob job = jobs.findById(entry.getKey()).orElse(null);
            if (job == null) {
                continue;
            }
            // 발송 중 취소가 들어온 행사: 미전송 배달만 취소하고 SENT 는 그대로
            LocalizedText eventName = eventAlive(job.getEventId()) ? eventName(job.getEventId()) : null;
            if (eventName == null) {
                cancelJob(job);
                continue;
            }
            Map<SupportedLocale, String> payloads = new HashMap<>();
            for (PushDelivery delivery : entry.getValue()) {
                if (tx.execute(status -> deliveries.claim(delivery.getId(), now)) != 1) {
                    continue;
                }
                // 문구 렌더링·전송·결과 기록 중 예외는 이 배달만 실패(statusCode 0)로 기록하고 다음 배달로 간다
                try {
                    SupportedLocale locale = locales.computeIfAbsent(delivery.getUserId(), this::localeOf);
                    String payload = payloads.computeIfAbsent(locale, l -> renderer.render(job.getKind(), job.getEventId(), eventName, l));
                    if (send(delivery, payload, now) == PushDeliveryStatus.SENT) {
                        sent++;
                    }
                } catch (RuntimeException e) {
                    log.error("[푸시 배달 처리 실패] deliveryId={}, jobId={}: 실패로 기록하고 다음 배달로", delivery.getId(), job.getId(), e);
                    recordFailure(delivery, e, now);
                }
            }
        }
        return sent;
    }

    // 전송은 트랜잭션 밖, 결과 기록은 짧은 트랜잭션. 410/404 는 구독 소프트 삭제
    private PushDeliveryStatus send(PushDelivery delivery, String payload, Instant now) {
        Optional<PushSubscription> subscription = subscriptions.findById(delivery.getSubscriptionId()).filter(s -> !s.isDeleted());
        SendResult result;
        if (subscription.isEmpty()) {
            result = new SendResult(410, "subscription deleted");
        } else {
            PushSubscription s = subscription.get();
            try {
                result = sender.send(new PushSubscriptionData(s.getEndpoint(), s.getP256dh(), s.getAuth()), payload);
            } catch (RuntimeException e) {
                result = SendResult.transportFailure(e);
            }
        }
        SendResult outcome = result;
        return tx.execute(status -> {
            // 잡이 지워지면 배달도 CASCADE 로 사라진다. 기록할 행이 없으면 그대로 끝낸다
            PushDelivery d = deliveries.findById(delivery.getId()).orElse(null);
            if (d == null) {
                log.warn("[푸시 결과 기록 건너뜀] deliveryId={}: 배달 행이 없음(잡 삭제)", delivery.getId());
                return PushDeliveryStatus.CANCELLED;
            }
            PushDeliveryStatus next = d.complete(outcome.statusCode(), outcome.error(), now);
            if (next == PushDeliveryStatus.GONE) {
                subscriptions.findById(d.getSubscriptionId()).ifPresent(s -> s.softDelete(now));
            }
            if (next != PushDeliveryStatus.SENT) {
                log.warn("[푸시 전송 실패] deliveryId={}, statusCode={}, attempts={}, next={}", d.getId(), outcome.statusCode(), d.getAttempts(), next);
            }
            return next;
        });
    }

    // 선점한 배달을 실패(statusCode 0)로 기록한다. 기록마저 실패하면 SENDING 으로 남고 고아 회수(5분)가 attempts 를 소모해 처리한다
    private void recordFailure(PushDelivery delivery, RuntimeException cause, Instant now) {
        try {
            tx.executeWithoutResult(status -> deliveries.findById(delivery.getId())
                    .filter(d -> d.getStatus() == PushDeliveryStatus.SENDING)
                    .ifPresent(d -> d.complete(0, SendResult.errorSummary(cause), now)));
        } catch (RuntimeException e) {
            log.error("[푸시 배달 실패 기록 실패] deliveryId={}: 고아 회수에 맡김", delivery.getId(), e);
        }
    }

    private int cancelJob(PushJob job) {
        return tx.execute(status -> {
            // 행사 물리 삭제로 잡이 CASCADE 삭제됐으면 취소할 것이 없다
            PushJob managed = jobs.findById(job.getId()).orElse(null);
            if (managed == null) {
                return 0;
            }
            boolean cancelledJob = managed.cancel();
            int cancelledDeliveries = deliveries.cancelUnsentByJobIds(List.of(managed.getId()));
            log.info("[푸시 잡 취소] jobId={}, eventId={}, job={}, cancelledDeliveries={}", managed.getId(), managed.getEventId(), cancelledJob, cancelledDeliveries);
            return cancelledJob ? 1 : 0;
        });
    }

    // 삭제됐거나 CANCELLED 인 행사에는 보내지 않는다
    private boolean eventAlive(Long eventId) {
        return eventQuery.find(eventId).map(e -> e.status() != EventSnapshot.Status.CANCELLED).orElse(false);
    }

    private LocalizedText eventName(Long eventId) {
        try {
            return eventQuery.view(eventId).name();
        } catch (NotFoundException e) {
            return null;
        }
    }

    private SupportedLocale localeOf(Long userId) {
        return accountQuery.find(userId).map(AccountSnapshot::locale).orElse(SupportedLocale.KO);
    }
}
