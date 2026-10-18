package com.janspace.notification.infrastructure;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.notification.application.SchedulePushJobs;
import com.janspace.notification.application.SubscriptionCommand;
import com.janspace.setlist.application.event.SetlistMerged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 다른 기능의 도메인 이벤트 수신. 발행 트랜잭션 커밋 뒤 새 트랜잭션에서 처리하고, 트랜잭션 밖 발행은 즉시 처리한다.
 * SetlistMerged(setlist) 는 SETLIST_CONFIRMED 잡 예약으로 잇는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushEventListeners {

    private final SchedulePushJobs schedulePushJobs;
    private final SubscriptionCommand subscriptionCommand;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(EventScheduleChanged event) {
        log.info("[행사 일정 변경 수신] eventId={}", event.eventId());
        schedulePushJobs.onEventScheduleChanged(event.eventId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(UserWithdrawn event) {
        subscriptionCommand.withdraw(event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(SetlistMerged event) {
        log.info("[셋리 확정 수신] eventId={}", event.eventId());
        schedulePushJobs.onSetlistMerged(event.eventId());
    }
}
