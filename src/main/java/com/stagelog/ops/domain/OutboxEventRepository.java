package com.stagelog.ops.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // 오래된 PENDING 부터 :limit 건을 잠근다. 다른 Relay(다른 서버)가 잠근 행은 건너뛰어 같은 행을 동시에 발행하지 않는다 (트랜잭션 안에서만 호출)
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE status = 'PENDING'
             ORDER BY created_at, id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("limit") int limit);

    List<OutboxEvent> findAllByMessageIdIn(Collection<String> messageIds);
}
