package com.janspace.event.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventSourceChangeRepository extends JpaRepository<EventSourceChange, Long> {

    // (event, field) 당 PENDING 1건은 애플리케이션 규칙이라 DB 가 막지 않는다. 경합으로 2건이 생겨도 접기가 영구히 깨지지 않게 최신 1건을 고른다
    Optional<EventSourceChange> findFirstByEventIdAndFieldAndStatusOrderByIdDesc(Long eventId, SourceChangeField field,
                                                                               SourceChangeStatus status);

    List<EventSourceChange> findAllByEventIdAndStatus(Long eventId, SourceChangeStatus status);

    void deleteAllByEventId(Long eventId);

    List<EventSourceChange> findAllByStatusOrderByDetectedAtDescIdDesc(SourceChangeStatus status);

    boolean existsByEventIdAndFieldAndStatusAndProposedValue(Long eventId, SourceChangeField field, SourceChangeStatus status,
                                                             String proposedValue);
}
