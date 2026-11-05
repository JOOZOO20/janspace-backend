package com.stagelog.event.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface EventSourceChangeRepository extends JpaRepository<EventSourceChange, Long> {

    // (event, field) 당 PENDING 1건은 애플리케이션 규칙이라 DB 가 막지 않는다. 경합으로 2건이 생겨도 접기가 영구히 깨지지 않게 최신 1건을 고른다.
    // 잠금 읽기(FOR UPDATE)라 트랜잭션 스냅샷이 아니라 최신 커밋을 본다: 행사 행 잠금을 기다린 뒤 앞 트랜잭션이 만든 PENDING 을 놓치지 않는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EventSourceChange> findFirstByEventIdAndFieldAndStatusOrderByIdDesc(Long eventId, SourceChangeField field,
                                                                               SourceChangeStatus status);

    List<EventSourceChange> findAllByEventIdAndStatus(Long eventId, SourceChangeStatus status);

    void deleteAllByEventId(Long eventId);

    List<EventSourceChange> findAllByStatusOrderByDetectedAtDescIdDesc(SourceChangeStatus status);

    boolean existsByEventIdAndFieldAndStatusAndProposedValue(Long eventId, SourceChangeField field, SourceChangeStatus status,
                                                             String proposedValue);
}
