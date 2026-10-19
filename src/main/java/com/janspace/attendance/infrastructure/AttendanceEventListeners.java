package com.janspace.attendance.infrastructure;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.attendance.application.AttendanceCommand;
import com.janspace.attendance.application.DistanceRecalculation;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.application.event.EventVenueChanged;
import com.janspace.event.application.event.VenueCoordinatesChanged;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 다른 기능의 도메인 이벤트 수신. 발행 트랜잭션이 커밋된 뒤 새 트랜잭션에서 처리한다.
 * fallbackExecution: 트랜잭션 밖에서 발행되면(테스트·배치) 즉시 처리한다.
 * 처리 실패는 ERROR 로그만 남기고 삼킨다(at-most-once). 거리는 그 사용자의 다음 기록 쓰기가 체인 전체를 다시 계산해 메운다 (설계 §10).
 */
@Slf4j
@Component
public class AttendanceEventListeners {

    private final DistanceRecalculation recalculation;
    private final AttendanceCommand attendanceCommand;
    private final TransactionTemplate requiresNew;

    public AttendanceEventListeners(DistanceRecalculation recalculation, AttendanceCommand attendanceCommand,
                                    PlatformTransactionManager transactionManager) {
        this.recalculation = recalculation;
        this.attendanceCommand = attendanceCommand;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(EventVenueChanged event) {
        log.info("[행사 공연장 변경 수신] eventId={}", event.eventId());
        run(event, () -> recalculation.forEvent(event.eventId()));
    }

    // 날짜·시작 시각이 바뀌면 체인 순서가 바뀌어 그 행사 기록자의 거리·연속 구간이 달라진다
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(EventScheduleChanged event) {
        log.info("[행사 일정 변경 수신 - 거리 재계산] eventId={}", event.eventId());
        run(event, () -> recalculation.forEvent(event.eventId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(VenueCoordinatesChanged event) {
        log.info("[공연장 좌표 변경 수신] venueId={}", event.venueId());
        run(event, () -> recalculation.forVenue(event.venueId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(UserWithdrawn event) {
        run(event, () -> attendanceCommand.withdraw(event.userId()));
    }

    // 트랜잭션 경계를 try 안에 둬야 커밋 시점 실패(제약 위반·rollback-only)까지 잡힌다
    private void run(Object event, Runnable work) {
        try {
            requiresNew.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            log.error("[이벤트 처리 실패 - 기록] event={}: 롤백하고 요청 흐름은 유지, 보정 필요", event, e);
        }
    }
}
