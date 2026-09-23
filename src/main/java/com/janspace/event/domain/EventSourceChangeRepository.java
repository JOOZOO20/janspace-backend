package com.janspace.event.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSourceChangeRepository extends JpaRepository<EventSourceChange, Long> {

    Optional<EventSourceChange> findByEventIdAndFieldAndStatus(Long eventId, SourceChangeField field, SourceChangeStatus status);

    List<EventSourceChange> findAllByEventIdAndStatus(Long eventId, SourceChangeStatus status);

    List<EventSourceChange> findAllByStatusOrderByDetectedAtDescIdDesc(SourceChangeStatus status);

    boolean existsByEventIdAndFieldAndStatusAndProposedValue(Long eventId, SourceChangeField field, SourceChangeStatus status,
                                                             String proposedValue);
}
