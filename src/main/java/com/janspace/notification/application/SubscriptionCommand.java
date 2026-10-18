package com.janspace.notification.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.notification.domain.PushDeliveryRepository;
import com.janspace.notification.domain.PushSubscription;
import com.janspace.notification.domain.PushSubscriptionRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구독 등록(endpoint 기준 upsert)과 탈퇴 정리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionCommand {

    private final PushSubscriptionRepository subscriptions;
    private final PushDeliveryRepository deliveries;

    // 같은 endpoint 는 한 행: 키·사용자를 갱신하고 삭제된 행이면 되살린다
    public PushSubscription subscribe(Long userId, String endpoint, String p256dh, String auth, String userAgent) {
        validate(endpoint, p256dh, auth);
        PushSubscription subscription = subscriptions.findByEndpoint(endpoint)
                .map(existing -> {
                    existing.renew(userId, p256dh, auth, userAgent);
                    return existing;
                })
                .orElseGet(() -> PushSubscription.of(userId, endpoint, p256dh, auth, userAgent));
        subscription = subscriptions.save(subscription);
        log.info("[푸시 구독 등록] userId={}, subscriptionId={}", userId, subscription.getId());
        return subscription;
    }

    // 탈퇴: 구독 소프트 삭제 + 미전송 배달 취소
    public void withdraw(Long userId) {
        Instant now = Instant.now();
        List<PushSubscription> rows = subscriptions.findAllByUserIdAndDeletedAtIsNull(userId);
        rows.forEach(s -> s.softDelete(now));
        int cancelled = deliveries.cancelUnsentByUserId(userId);
        log.info("[탈퇴 푸시 정리] userId={}, subscriptions={}, cancelledDeliveries={}", userId, rows.size(), cancelled);
    }

    private static void validate(String endpoint, String p256dh, String auth) {
        if (endpoint == null || !endpoint.startsWith("https://") || endpoint.length() > PushSubscription.MAX_ENDPOINT_LENGTH) {
            throw new BadRequestException(ErrorCode.PUSH_SUBSCRIPTION_INVALID, "endpoint 는 512자 이하의 https URL 이어야 합니다.");
        }
        if (isBlankOrLong(p256dh) || isBlankOrLong(auth)) {
            throw new BadRequestException(ErrorCode.PUSH_SUBSCRIPTION_INVALID, "keys.p256dh 와 keys.auth 가 필요합니다.");
        }
    }

    private static boolean isBlankOrLong(String key) {
        return key == null || key.isBlank() || key.length() > PushSubscription.MAX_KEY_LENGTH;
    }
}
