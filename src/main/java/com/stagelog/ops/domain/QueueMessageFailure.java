package com.stagelog.ops.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Queue 메시지 처리 실패 (메시지당 1행). 쓰기는 저장소의 native upsert 로만 한다. DLQ 메시지에는 오류 정보가 없어 관리 API 가 마지막 오류를 여기서 읽는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "queue_message_failures")
public class QueueMessageFailure extends BaseEntity {

    @Id
    @Column(name = "message_id", nullable = false, length = 36, columnDefinition = "CHAR(36)")
    private String messageId;

    @Column(name = "queue_name", nullable = false, length = 50)
    private String queueName;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", nullable = false, length = 1000)
    private String lastError;

    @Column(nullable = false)
    private boolean permanent;

    @Column(name = "last_failed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant lastFailedAt;
}
