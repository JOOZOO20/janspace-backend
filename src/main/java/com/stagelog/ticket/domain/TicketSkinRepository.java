package com.stagelog.ticket.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketSkinRepository extends JpaRepository<TicketSkin, Long> {

    List<TicketSkin> findAllByOrderByIdAsc();

    List<TicketSkin> findAllByIsDefaultTrue();
}
