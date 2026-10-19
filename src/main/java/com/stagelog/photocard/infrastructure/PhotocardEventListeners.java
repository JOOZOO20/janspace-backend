package com.stagelog.photocard.infrastructure;

import com.stagelog.account.application.event.UserWithdrawn;
import com.stagelog.attendance.application.event.AttendanceDeleted;
import com.stagelog.photocard.application.DeletePhotocardUseCase;
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
 * 처리 실패는 ERROR 로그만 남기고 삼킨다(at-most-once). 보정은 로그의 ID 로 수동 재처리 (설계 §10).
 */
@Slf4j
@Component
public class PhotocardEventListeners {

    private final DeletePhotocardUseCase deletePhotocard;
    private final TransactionTemplate requiresNew;

    public PhotocardEventListeners(DeletePhotocardUseCase deletePhotocard, PlatformTransactionManager transactionManager) {
        this.deletePhotocard = deletePhotocard;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(AttendanceDeleted event) {
        log.info("[기록 삭제 수신] attendanceId={}", event.attendanceId());
        run(event, () -> deletePhotocard.unlinkAttendance(event.attendanceId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(UserWithdrawn event) {
        run(event, () -> deletePhotocard.withdraw(event.userId()));
    }

    // 트랜잭션 경계를 try 안에 둬야 커밋 시점 실패(제약 위반·rollback-only)까지 잡힌다
    private void run(Object event, Runnable work) {
        try {
            requiresNew.executeWithoutResult(status -> work.run());
        } catch (RuntimeException e) {
            log.error("[이벤트 처리 실패 - 포토카드] event={}: 롤백하고 요청 흐름은 유지, 보정 필요", event, e);
        }
    }
}
