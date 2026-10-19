package com.stagelog.event.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByIdAndDeletedAtIsNull(Long id);

    // 목록·스냅샷 응답이 공연장을 함께 쓰므로 한 번에 읽는다 (LAZY 그대로면 행사마다 공연장 SELECT)
    @EntityGraph(attributePaths = "venue")
    List<Event> findAllByIdInAndDeletedAtIsNull(Collection<Long> ids);

    List<Event> findAllByVenueIdAndDeletedAtIsNull(Long venueId);

    // 같은 시즌(투어) 회차. 공연 시간 추정·재예약 대상 (행사 수가 적어 시즌 인덱스는 두지 않는다)
    List<Event> findAllBySeasonAndDeletedAtIsNull(String season);

    // 목록: [from, to) 범위의 event_date. 정렬은 Pageable 로 (UPCOMING 오름차순 / PAST 내림차순)
    @EntityGraph(attributePaths = "venue")
    @Query("""
            select e from Event e
            where e.deletedAt is null
              and (:from is null or e.eventDate >= :from)
              and (:to is null or e.eventDate < :to)""")
    Page<Event> findPage(@Param("from") LocalDate from, @Param("to") LocalDate to, Pageable pageable);

    // 출처 매칭: 같은 현지 날짜의 행사 (Ticketmaster·setlist.fm)
    List<Event> findAllByEventDateAndDeletedAtIsNull(LocalDate eventDate);

    // KOPIS 보강 대상: 기간 안의 활성 행사
    List<Event> findAllByStatusAndDeletedAtIsNullAndEventDateBetween(EventStatus status, LocalDate from, LocalDate to);

    // D-3 알림 대상: 활성이고 종료 시각이 없는 임박 행사
    List<Event> findAllByStatusAndDeletedAtIsNullAndEndsAtIsNullAndEventDateBetween(EventStatus status, LocalDate from, LocalDate to);
}
