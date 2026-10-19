package com.janspace.notification.infrastructure;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.notification.application.SchedulePushJobs;
import com.janspace.notification.application.SubscriptionCommand;
import com.janspace.setlist.application.event.SetlistMerged;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 다른 기능의 도메인 이벤트 수신. 발행 트랜잭션 커밋 뒤 새 트랜잭션에서 처리하고, 트랜잭션 밖 발행은 즉시 처리한다.
 * SetlistMerged(setlist) 는 SETLIST_CONFIRMED 잡 예약으로 잇는다.
 * 처리 실패는 ERROR 로그만 남기고 삼킨다(at-most-once). 발행 요청은 이미 커밋됐으므로 실패시키지 않고, 같은 이벤트의 다음 리스너도 막지 않는다.
 * 보정: 일정 변경 누락은 DispatchScheduler 의 하루 1회 재예약이 메운다. 나머지는 로그의 ID 로 수동 재처리 (설계 §10).
 */
@Slf4j
@Component
public class PushEventListeners {

    private final SchedulePushJobs schedulePushJobs;
    private final SubscriptionCommand subscriptionCommand;
    private final TransactionTemplate requiresNew;

    public PushEventListeners(SchedulePushJobs schedulePushJobs, SubscriptionCommand subscriptionCommand,
                              PlatformTransactionManager transactionManager) {
        this.schedulePushJobs = schedulePushJobs;
        this.subscriptionCommand = subscriptionCommand;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(EventScheduleChanged event) {
        log.info("[행사 일정 변경 수신] eventId={}", event.eventId());
        run(event, () -> schedulePushJobs.onEventScheduleChanged(event.eventId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(UserWithdrawn event) {
        run(event, () -> subscriptionCommand.withdraw(event.userId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(SetlistMerged event) {
        log.info("[셋리 확정 수신] eventId={}", event.eventId());
        run(event, () -> schedulePushJobs.onSetlistMerged(event.eventId()));
    }

    // 트랜잭션 경계를 try 안에 둬야 커밋 시점 실패(제약 위반·rollback-only)까지 잡힌다
    private void run(Object event, Runnable work) {
        try {
            requiresNew.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            log.error("[이벤트 처리 실패 - 푸시] event={}: 롤백하고 요청 흐름은 유지, 보정 필요", event, e);
        }
    }
}
