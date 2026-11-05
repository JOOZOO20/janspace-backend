package com.stagelog.event.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByIdAndDeletedAtIsNull(Long id);

    // SELECT ... FOR UPDATE. 같은 행사를 접는 트랜잭션끼리 직렬화한다 (공급원이 다른 동기화는 동시에 돌 수 있다. 트랜잭션 안에서만 호출)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id and e.deletedAt is null")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);

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

    // 삭제 배치의 참조 확인 (poster_key 인덱스 없음: 행사 수가 적고 하루 1회라 전체 스캔으로 둔다). 삭제된 행사도 참조로 센다
    boolean existsByPosterKey(String posterKey);

    // key 백필 대상: 버전 key 이전의 고정 key(events/{id}/poster.{ext}). 삭제된 행사도 포함
    List<Event> findAllByPosterKeyLike(String pattern);
}
