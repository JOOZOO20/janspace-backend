package com.stagelog.notification.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.notification.domain.PushDeliveryRepository;
import com.stagelog.notification.domain.PushSubscription;
import com.stagelog.notification.domain.PushSubscriptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구독 등록(endpoint 기준 upsert)·해지와 탈퇴 정리. 구독이 없어지면 그 구독으로 아직 나가지 않은 배달을 함께 취소한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionCommand {

    private final PushSubscriptionRepository subscriptions;
    private final PushDeliveryRepository deliveries;

    // 같은 endpoint 는 한 행: 키·사용자를 갱신하고 삭제된 행이면 되살린다.
    // 다른 사용자의 구독이었다면 그 앞으로 쌓인 미전송 배달을 취소한다 (이전 사용자의 알림이 새 사용자 기기로 가지 않게)
    public PushSubscription subscribe(Long userId, String endpoint, String p256dh, String auth, String userAgent) {
        validate(endpoint, p256dh, auth);
        Optional<PushSubscription> existing = subscriptions.findByEndpoint(endpoint);
        PushSubscription subscription;
        if (existing.isPresent()) {
            subscription = existing.get();
            if (!subscription.getUserId().equals(userId)) {
                int cancelled = deliveries.cancelUnsentBySubscriptionId(subscription.getId());
                log.info("[푸시 구독 소유자 변경] subscriptionId={}, fromUserId={}, toUserId={}, cancelledDeliveries={}",
                        subscription.getId(), subscription.getUserId(), userId, cancelled);
            }
            subscription.renew(userId, p256dh, auth, userAgent);
        } else {
            subscription = PushSubscription.of(userId, endpoint, p256dh, auth, userAgent);
        }
        subscription = subscriptions.save(subscription);
        log.info("[푸시 구독 등록] userId={}, subscriptionId={}", userId, subscription.getId());
        return subscription;
    }

    // 해지: 본인의 활성 구독만 소프트 삭제하고 그 구독의 미전송(PENDING/RETRY) 배달을 취소한다. 이미 SENDING 인 배달은 막지 못한다.
    // 없거나 남의 구독이면 아무것도 하지 않는다. "이 endpoint 로 내 알림이 가지 않는다"는 결과는 이미 성립하고, 남의 구독 존재도 드러내지 않는다
    public void unsubscribe(Long userId, String endpoint) {
        Optional<PushSubscription> mine = subscriptions.findByEndpoint(endpoint)
                .filter(s -> !s.isDeleted() && s.getUserId().equals(userId));
        if (mine.isEmpty()) {
            log.info("[푸시 구독 해지 건너뜀] userId={}: 본인의 활성 구독이 아님", userId);
            return;
        }
        PushSubscription subscription = mine.get();
        subscription.softDelete(Instant.now());
        int cancelled = deliveries.cancelUnsentBySubscriptionId(subscription.getId());
        log.info("[푸시 구독 해지] userId={}, subscriptionId={}, cancelledDeliveries={}", userId, subscription.getId(), cancelled);
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
