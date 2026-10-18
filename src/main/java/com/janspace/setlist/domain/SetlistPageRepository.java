package com.janspace.setlist.domain;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SetlistPageRepository extends JpaRepository<SetlistPage, Long> {

    List<SetlistPage> findAllByEventIdIn(Collection<Long> eventIds);

    List<SetlistPage> findAllByMergedAtIsNotNull();

    // SELECT ... FOR UPDATE
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SetlistPage p where p.eventId = :eventId")
    Optional<SetlistPage> findByIdForUpdate(@Param("eventId") Long eventId);

    // 없으면 빈 페이지를 만든다. 이미 있으면(동시에 만든 쪽이 있으면 그 커밋을 기다린 뒤) 아무것도 바꾸지 않는다.
    // 예외 없이 끝나므로 호출자 트랜잭션이 rollback-only 가 되지 않는다. 행사 존재(FK)는 호출자가 먼저 확인한다
    @Modifying
    @Query(value = "INSERT INTO setlist_pages (event_id, order_confirmed, version, created_at, updated_at) "
            + "VALUES (:eventId, FALSE, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE event_id = event_id",
            nativeQuery = true)
    void insertIfAbsent(@Param("eventId") Long eventId);
}
