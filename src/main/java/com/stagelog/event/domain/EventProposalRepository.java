package com.stagelog.event.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventProposalRepository extends JpaRepository<EventProposal, Long> {

    List<EventProposal> findAllByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    List<EventProposal> findAllByOrderByCreatedAtDescIdDesc();

    void deleteAllByEventId(Long eventId);
}
