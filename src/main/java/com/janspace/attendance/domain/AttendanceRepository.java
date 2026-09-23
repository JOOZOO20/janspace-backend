package com.janspace.attendance.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {

    // 소프트 삭제 행 포함 (되살림용). unique(user_id, event_id) 라 최대 1건
    Optional<Attendance> findByUserIdAndEventId(Long userId, Long eventId);

    Optional<Attendance> findByIdAndDeletedAtIsNull(Long id);

    List<Attendance> findAllByUserIdAndDeletedAtIsNull(Long userId);

    @Query("select distinct a.userId from Attendance a where a.eventId in :eventIds and a.deletedAt is null")
    List<Long> findUserIdsByEventIds(@Param("eventIds") Collection<Long> eventIds);

    @Query("""
            select a.userId from Attendance a
            where a.eventId = :eventId and a.deletedAt is null and a.plannedAt is not null and a.attendedAt is null""")
    List<Long> findPlannedUserIds(@Param("eventId") Long eventId);

    @Query("""
            select a.userId from Attendance a
            where a.eventId = :eventId and a.deletedAt is null and a.attendedAt is not null and a.bestSongId is null""")
    List<Long> findRecordedWithoutBestSongUserIds(@Param("eventId") Long eventId);

    // 별자리: 행사별 기록(갔어요 이상, 미삭제) 수. [eventId, count]
    @Query("""
            select a.eventId, count(a) from Attendance a
            where a.attendedAt is not null and a.deletedAt is null and a.eventId in :eventIds
            group by a.eventId""")
    List<Object[]> countStarsByEvent(@Param("eventIds") Collection<Long> eventIds);

    @Query("""
            select a.eventId, count(a) from Attendance a
            where a.attendedAt is not null and a.deletedAt is null
            group by a.eventId""")
    List<Object[]> countStarsAll();
}
