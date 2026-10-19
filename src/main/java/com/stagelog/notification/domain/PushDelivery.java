package com.stagelog.notification.domain;

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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 잡 x 구독 1건의 배달.
 * PENDING/RETRY -> SENDING(선점, attempts+1: 저장소 조건부 UPDATE) -> SENT | GONE | RETRY(백오프, attempts<3) | FAILED.
 * PENDING/RETRY -> CANCELLED(행사 취소·탈퇴: 저장소 조건부 UPDATE). FAILED -> RETRY(관리자 수동, attempts 유지).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "push_deliveries")
public class PushDelivery extends BaseEntity {

    public static final int MAX_ERROR_LENGTH = 500;
    public static final String ORPHANED = "orphaned";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PushDeliveryStatus status = PushDeliveryStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", columnDefinition = "DATETIME(6)")
    private Instant nextAttemptAt;

    @Column(name = "sending_started_at", columnDefinition = "DATETIME(6)")
    private Instant sendingStartedAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "sent_at", columnDefinition = "DATETIME(6)")
    private Instant sentAt;

    private PushDelivery(Long jobId, Long subscriptionId, Long userId) {
        this.jobId = Objects.requireNonNull(jobId);
        this.subscriptionId = Objects.requireNonNull(subscriptionId);
        this.userId = Objects.requireNonNull(userId);
    }

    public static PushDelivery pending(Long jobId, Long subscriptionId, Long userId) {
        return new PushDelivery(jobId, subscriptionId, userId);
    }

    /**
     * 전송 결과 반영 (SENDING 에서만). statusCode 0 = 네트워크·IO 실패.
     * 2xx SENT / 404·410 GONE / 429·5xx·0 -> attempts<3 이면 RETRY 아니면 FAILED / 그 외 4xx FAILED.
     */
    public PushDeliveryStatus complete(int statusCode, String error, Instant now) {
        requireStatus(PushDeliveryStatus.SENDING);
        if (statusCode >= 200 && statusCode < 300) {
            status = PushDeliveryStatus.SENT;
            sentAt = now;
            lastError = null;
        } else if (statusCode == 404 || statusCode == 410) {
            status = PushDeliveryStatus.GONE;
            lastError = truncate(error == null ? "HTTP " + statusCode : error);
        } else if (statusCode == 0 || statusCode == 429 || statusCode >= 500) {
            retryOrFail(error == null ? "HTTP " + statusCode : error, now);
        } else {
            status = PushDeliveryStatus.FAILED;
            lastError = truncate(error == null ? "HTTP " + statusCode : error);
        }
        return status;
    }

    // 고아 SENDING 회수. 이미 소모한 attempts 로 판정한다
    public PushDeliveryStatus recoverOrphan(Instant now) {
        requireStatus(PushDeliveryStatus.SENDING);
        retryOrFail(ORPHANED, now);
        return status;
    }

    // 선점의 엔티티 표현. 운영 경로는 동시성 때문에 PushDeliveryRepository.claim(조건부 UPDATE) 을 쓰고, 이 메서드는 같은 전이를 정의한다
    public void markSending(Instant now) {
        if (status != PushDeliveryStatus.PENDING && status != PushDeliveryStatus.RETRY) {
            throw new IllegalStateException("PENDING/RETRY 배달만 선점할 수 있습니다: id=" + id + ", status=" + status);
        }
        status = PushDeliveryStatus.SENDING;
        sendingStartedAt = now;
        attempts++;
    }

    // 관리자 수동 재시도. attempts 는 초기화하지 않는다
    public boolean retryManually(Instant now) {
        if (status != PushDeliveryStatus.FAILED) {
            return false;
        }
        status = PushDeliveryStatus.RETRY;
        nextAttemptAt = now;
        return true;
    }

    private void retryOrFail(String error, Instant now) {
        lastError = truncate(error);
        if (BackoffPolicy.canRetry(attempts)) {
            status = PushDeliveryStatus.RETRY;
            nextAttemptAt = now.plus(BackoffPolicy.delayAfter(attempts));
        } else {
            status = PushDeliveryStatus.FAILED;
        }
    }

    private void requireStatus(PushDeliveryStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("배달 상태가 " + expected + " 가 아닙니다: id=" + id + ", status=" + status);
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
