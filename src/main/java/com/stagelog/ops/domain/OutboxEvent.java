package com.stagelog.ops.domain;

import com.stagelog.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 발행할 Queue 메시지 한 건 (Transactional Outbox). 업무 데이터와 같은 트랜잭션에서 PENDING 으로 만들어지고, Relay 가 SQS 발행 성공 뒤 PUBLISHED 로 바꾼다.
 * 발행 이력이므로 물리 삭제만 의미가 있어 BaseEntity 를 쓴다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "outbox_events")
public class OutboxEvent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true, length = 36, columnDefinition = "CHAR(36)")
    private String messageId;

    @Column(name = "queue_name", nullable = false, length = 50)
    private String queueName;

    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "published_at", columnDefinition = "DATETIME(6)")
    private Instant publishedAt;

    public OutboxEvent(String messageId, String queueName, String payload) {
        this.messageId = messageId;
        this.queueName = queueName;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
    }

    public void markPublished(Instant at) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = at;
    }

    // 일시 실패는 PENDING 으로 남아 다음 Relay 주기에 다시 발행한다
    public void failAttempt(boolean rejected) {
        this.attempts++;
        if (rejected) {
            this.status = OutboxStatus.FAILED;
        }
    }
}
