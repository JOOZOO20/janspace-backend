package com.stagelog.ops.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

    // 최신순. 같은 시각이면 id 가 큰 쪽이 나중
    List<SyncRun> findAllByOrderByStartedAtDescIdDesc(Limit limit);

    Optional<SyncRun> findFirstBySourceAndStatusOrderByStartedAtDescIdDesc(SyncSource source, SyncStatus status);

    Optional<SyncRun> findFirstBySourceOrderByStartedAtDescIdDesc(SyncSource source);

    List<SyncRun> findAllBySourceAndStatus(SyncSource source, SyncStatus status);
}
