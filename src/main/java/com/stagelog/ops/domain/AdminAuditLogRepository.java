package com.stagelog.ops.domain;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 감사 로그 저장소. append-only 를 지키려고 JpaRepository 대신 Repository 를 상속해 저장과 조회만 연다
 * (package-architecture.md 의 JpaRepository 직접 상속 규칙 예외, 적용 범위는 이 저장소). 삭제·일괄 수정 메서드를 추가하지 않는다.
 */
public interface AdminAuditLogRepository extends Repository<AdminAuditLog, Long> {

    AdminAuditLog save(AdminAuditLog log);

    // 조건이 null 이면 그 조건을 걸지 않는다. from 포함, to 제외. 정렬은 Pageable 로 (최신순)
    @Query("""
            select a from AdminAuditLog a
            where (:targetType is null or a.targetType = :targetType)
              and (:targetId is null or a.targetId = :targetId)
              and (:actorId is null or a.actorId = :actorId)
              and (:from is null or a.createdAt >= :from)
              and (:to is null or a.createdAt < :to)""")
    Page<AdminAuditLog> search(@Param("targetType") String targetType, @Param("targetId") String targetId,
                               @Param("actorId") String actorId, @Param("from") Instant from, @Param("to") Instant to,
                               Pageable pageable);
}
