package com.stagelog.ops.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentActionRequestRepository extends JpaRepository<AgentActionRequest, Long> {

    Optional<AgentActionRequest> findFirstByTypeAndTargetRefAndStatus(AgentActionType type, String targetRef,
                                                                      AgentRequestStatus status);

    List<AgentActionRequest> findAllByStatusOrderByIdDesc(AgentRequestStatus status, Limit limit);

    // 승인·거절이 동시에 와도 한쪽만 PENDING 을 본다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AgentActionRequest r where r.id = :id")
    Optional<AgentActionRequest> findByIdForUpdate(@Param("id") Long id);
}
