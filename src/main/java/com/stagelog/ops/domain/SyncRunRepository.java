package com.stagelog.ops.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

    // 최신순. 같은 시각이면 id 가 큰 쪽이 나중
    List<SyncRun> findAllByOrderByStartedAtDescIdDesc(Limit limit);

    Optional<SyncRun> findFirstBySourceAndStatusOrderByStartedAtDescIdDesc(SyncSource source, SyncStatus status);

    // Lease 로 실행했고 그 Lease 를 이제 아무도 쥐고 있지 않은 RUNNING (token 이 바뀌었거나 만료·해제됨) = 소유자가 종료 기록 없이 사라짐
    @Query(value = """
            SELECT r.* FROM sync_runs r
             WHERE r.source = :source AND r.status = 'RUNNING' AND r.fencing_token IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM job_leases l
                                WHERE l.lease_key = :leaseKey AND l.fencing_token = r.fencing_token
                                  AND l.owner_id IS NOT NULL AND l.expires_at >= NOW(6))""", nativeQuery = true)
    List<SyncRun> findLeaseExpiredRunning(@Param("source") String source, @Param("leaseKey") String leaseKey);

    // Lease 없이 기록한 RUNNING 중 시작 후 :minutes 분이 지난 것
    @Query(value = """
            SELECT r.* FROM sync_runs r
             WHERE r.source = :source AND r.status = 'RUNNING' AND r.fencing_token IS NULL
               AND r.started_at < NOW(6) - INTERVAL :minutes MINUTE""", nativeQuery = true)
    List<SyncRun> findUnfencedRunningStartedBefore(@Param("source") String source, @Param("minutes") long minutes);

    Optional<SyncRun> findFirstBySourceOrderByStartedAtDescIdDesc(SyncSource source);
}
