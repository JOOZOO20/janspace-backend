package com.janspace.notification.domain;

import com.janspace.common.entity.SoftDeletableBaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 브라우저 푸시 구독 한 건. endpoint 가 유일키라 같은 endpoint 재등록은 키·사용자를 갱신하고, 삭제된 행이면 되살린다.
 * push_deliveries 가 FK 로 참조하므로 410 GONE·탈퇴는 소프트 삭제다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "push_subscriptions")
public class PushSubscription extends SoftDeletableBaseEntity {

    public static final int MAX_ENDPOINT_LENGTH = 512;
    public static final int MAX_KEY_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = MAX_ENDPOINT_LENGTH, unique = true)
    private String endpoint;

    @Column(nullable = false, length = MAX_KEY_LENGTH)
    private String p256dh;

    @Column(nullable = false, length = MAX_KEY_LENGTH)
    private String auth;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    private PushSubscription(Long userId, String endpoint) {
        this.userId = Objects.requireNonNull(userId);
        this.endpoint = Objects.requireNonNull(endpoint);
    }

    public static PushSubscription of(Long userId, String endpoint, String p256dh, String auth, String userAgent) {
        PushSubscription subscription = new PushSubscription(userId, endpoint);
        subscription.renew(userId, p256dh, auth, userAgent);
        return subscription;
    }

    // 같은 endpoint 재등록: 키·사용자 갱신 + 삭제 표시 해제
    public void renew(Long userId, String p256dh, String auth, String userAgent) {
        this.userId = Objects.requireNonNull(userId);
        this.p256dh = Objects.requireNonNull(p256dh);
        this.auth = Objects.requireNonNull(auth);
        this.userAgent = userAgent == null ? null : userAgent.substring(0, Math.min(userAgent.length(), 255));
        super.delete(null);
    }

    public void softDelete(Instant now) {
        super.delete(now);
    }
}
