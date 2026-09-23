package com.janspace.event.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByIdAndDeletedAtIsNull(Long id);

    List<Event> findAllByIdInAndDeletedAtIsNull(Collection<Long> ids);

    List<Event> findAllByVenueIdAndDeletedAtIsNull(Long venueId);

    // 목록: [from, to) 범위의 event_date. 정렬은 Pageable 로 (UPCOMING 오름차순 / PAST 내림차순)
    @Query("""
            select e from Event e
            where e.deletedAt is null
              and (:from is null or e.eventDate >= :from)
              and (:to is null or e.eventDate < :to)""")
    Page<Event> findPage(@Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    // D-3 알림 대상: 활성이고 종료 시각이 없는 임박 행사
    List<Event> findAllByStatusAndDeletedAtIsNullAndEndsAtIsNullAndEventDateBetween(EventStatus status, LocalDate from, LocalDate to);
}
