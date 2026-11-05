package com.stagelog.ops.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncItemFailureRepository extends JpaRepository<SyncItemFailure, Long> {

    List<SyncItemFailure> findAllBySyncRunId(Long syncRunId);
}
