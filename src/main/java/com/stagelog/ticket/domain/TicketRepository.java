package com.stagelog.ticket.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // 등록 순(id). 연번(serial)은 이 순서로 매긴다
    List<Ticket> findAllByUserIdOrderByIdAsc(Long userId);

    boolean existsByUserIdAndEventId(Long userId, Long eventId);
}
