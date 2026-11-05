package com.stagelog.ops.domain;

import java.util.List;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface QueueMessageFailureRepository extends JpaRepository<QueueMessageFailure, String> {

    // 실패 1회 기록. 같은 메시지의 이전 실패가 있으면 attempts 를 올리고 마지막 오류를 바꾼다
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO queue_message_failures (message_id, queue_name, attempts, last_error, permanent, last_failed_at, created_at, updated_at)
            VALUES (:messageId, :queue, 1, :error, :permanent, NOW(6), NOW(6), NOW(6)) AS next
            ON DUPLICATE KEY UPDATE attempts = queue_message_failures.attempts + 1, last_error = next.last_error,
                                    permanent = next.permanent, last_failed_at = next.last_failed_at, updated_at = next.updated_at""",
            nativeQuery = true)
    void record(@Param("messageId") String messageId, @Param("queue") String queue, @Param("error") String error,
                @Param("permanent") boolean permanent);

    List<QueueMessageFailure> findAllByMessageIdIn(Collection<String> messageIds);

    List<QueueMessageFailure> findAllByQueueNameInOrderByLastFailedAtDesc(Collection<String> queueNames);
}
