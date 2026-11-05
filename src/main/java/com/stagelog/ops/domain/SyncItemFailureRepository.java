package com.stagelog.ops.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncItemFailureRepository extends JpaRepository<SyncItemFailure, Long> {

    List<SyncItemFailure> findAllBySyncRunId(Long syncRunId);

    interface RunFailureCount {
        Long getSyncRunId();

        Long getFailures();
    }

    // 실행별 실패 항목 수. idx_run 을 쓴다
    @Query("select f.syncRunId as syncRunId, count(f) as failures from SyncItemFailure f where f.syncRunId in :runIds group by f.syncRunId")
    List<RunFailureCount> countByRunIds(@Param("runIds") Collection<Long> runIds);
}
