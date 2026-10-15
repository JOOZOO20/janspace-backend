package com.janspace.attendance.infrastructure;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.attendance.application.AttendanceCommand;
import com.janspace.attendance.application.DistanceRecalculation;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.application.event.EventVenueChanged;
import com.janspace.event.application.event.VenueCoordinatesChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 다른 기능의 도메인 이벤트 수신. 발행 트랜잭션이 커밋된 뒤 새 트랜잭션에서 처리한다.
 * fallbackExecution: 트랜잭션 밖에서 발행되면(테스트·배치) 즉시 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttendanceEventListeners {

    private final DistanceRecalculation recalculation;
    private final AttendanceCommand attendanceCommand;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(EventVenueChanged event) {
        log.info("[행사 공연장 변경 수신] eventId={}", event.eventId());
        recalculation.forEvent(event.eventId());
    }

    // 날짜·시작 시각이 바뀌면 체인 순서가 바뀌어 그 행사 기록자의 거리·연속 구간이 달라진다
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(EventScheduleChanged event) {
        log.info("[행사 일정 변경 수신 - 거리 재계산] eventId={}", event.eventId());
        recalculation.forEvent(event.eventId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(VenueCoordinatesChanged event) {
        log.info("[공연장 좌표 변경 수신] venueId={}", event.venueId());
        recalculation.forVenue(event.venueId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(UserWithdrawn event) {
        attendanceCommand.withdraw(event.userId());
    }
}
