package com.janspace.ticket.infrastructure;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.ticket.application.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 다른 기능의 도메인 이벤트 수신. 발행 트랜잭션이 커밋된 뒤 새 트랜잭션에서 처리한다.
 * fallbackExecution: 트랜잭션 밖에서 발행되면(테스트·배치) 즉시 처리한다.
 */
@Component
@RequiredArgsConstructor
public class TicketEventListeners {

    private final TicketService ticketService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(UserWithdrawn event) {
        ticketService.withdraw(event.userId());
    }
}
