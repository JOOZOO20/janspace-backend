package com.janspace.notification.application.port;

/**
 * 웹 푸시 전송 포트. 구현은 notification/infrastructure(WebPushSenderAdapter -> integration/webpush).
 * 전송 결과는 예외가 아니라 상태 코드로 돌려주고, 네트워크·IO 실패는 statusCode 0 이다. 상태 전이 판정은 PushDelivery.complete 가 한다.
 */
public interface PushSenderPort {

    record PushSubscriptionData(String endpoint, String p256dh, String auth) {
    }

    record SendResult(int statusCode, String error) {

        public static SendResult transportFailure(String error) {
            return new SendResult(0, error);
        }
    }

    SendResult send(PushSubscriptionData subscription, String payloadJson);
}
