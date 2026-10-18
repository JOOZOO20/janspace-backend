package com.janspace.notification.application.port;

/**
 * 웹 푸시 전송 포트. 구현은 notification/infrastructure(WebPushSenderAdapter -> integration/webpush).
 * 전송 결과는 예외가 아니라 상태 코드로 돌려주고, 네트워크·IO 실패는 statusCode 0 이다. 상태 전이 판정은 PushDelivery.complete 가 한다.
 * keysMissing 이 true 면(VAPID 키 미설정) 디스패처는 전송 단계를 건너뛰어 배달을 PENDING 으로 둔다. 목의 기본값(false)이 "키 있음"이 되도록 부정형이다.
 */
public interface PushSenderPort {

    record PushSubscriptionData(String endpoint, String p256dh, String auth) {
    }

    record SendResult(int statusCode, String error) {

        public static SendResult transportFailure(String error) {
            return new SendResult(0, error);
        }

        // last_error 는 관리자 응답에 나간다. 예외 메시지에는 구독 endpoint URL 이 섞이므로 클래스 이름만 남긴다
        public static SendResult transportFailure(Throwable e) {
            return transportFailure(errorSummary(e));
        }

        public static String errorSummary(Throwable e) {
            return e.getCause() == null ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + " <- " + e.getCause().getClass().getSimpleName();
        }
    }

    SendResult send(PushSubscriptionData subscription, String payloadJson);

    boolean keysMissing();
}
