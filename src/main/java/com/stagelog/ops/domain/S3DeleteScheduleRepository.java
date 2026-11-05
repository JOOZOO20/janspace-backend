package com.stagelog.ops.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface S3DeleteScheduleRepository extends JpaRepository<S3DeleteSchedule, Long> {

    // 지금(DB 시계)부터 days 일 뒤 삭제 예약. 이미 예약·삭제된 key 면 기한을 다시 잡고 삭제 표시를 지운다
    // (같은 내용이 다시 올라와 key 가 재사용된 뒤 또 교체된 경우, 마지막 교체부터 다시 days 일을 보장한다)
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO s3_delete_schedule (object_key, delete_after, deleted_at, reason, created_at, updated_at)
            VALUES (:key, NOW(6) + INTERVAL :days DAY, NULL, :reason, NOW(6), NOW(6)) AS next
            ON DUPLICATE KEY UPDATE delete_after = next.delete_after, deleted_at = NULL, reason = next.reason,
                                    updated_at = next.updated_at""", nativeQuery = true)
    void schedule(@Param("key") String key, @Param("days") int days, @Param("reason") String reason);

    // 기한이 지난(DB 시계) 미삭제 예약
    @Query(value = """
            SELECT * FROM s3_delete_schedule
            WHERE deleted_at IS NULL AND delete_after <= NOW(6)
            ORDER BY id LIMIT :limit""", nativeQuery = true)
    List<S3DeleteSchedule> findDue(@Param("limit") int limit);

    @Transactional
    @Modifying
    @Query(value = "UPDATE s3_delete_schedule SET deleted_at = NOW(6), updated_at = NOW(6) WHERE id = :id AND deleted_at IS NULL",
            nativeQuery = true)
    int markDeleted(@Param("id") Long id);
}
