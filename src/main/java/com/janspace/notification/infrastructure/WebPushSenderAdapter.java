package com.janspace.notification.infrastructure;

import com.janspace.integration.webpush.WebPushClient;
import com.janspace.notification.application.port.PushSenderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PushSenderPort 의 Web Push(VAPID) 구현. 전송 계층 예외(네트워크·키 미설정)는 statusCode 0 으로 돌려 재시도 판정에 맡긴다.
 */
@Component
@RequiredArgsConstructor
public class WebPushSenderAdapter implements PushSenderPort {

    private final WebPushClient webPushClient;

    @Override
    public SendResult send(PushSubscriptionData subscription, String payloadJson) {
        try {
            WebPushClient.Result result = webPushClient.send(subscription.endpoint(), subscription.p256dh(), subscription.auth(), payloadJson);
            return new SendResult(result.statusCode(), result.isSuccess() ? null : "HTTP " + result.statusCode() + " " + result.body());
        } catch (RuntimeException e) {
            return SendResult.transportFailure(e.toString());
        }
    }
}
