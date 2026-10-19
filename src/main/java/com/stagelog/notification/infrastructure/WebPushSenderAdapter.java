package com.stagelog.notification.infrastructure;

import com.stagelog.integration.webpush.WebPushClient;
import com.stagelog.notification.application.port.PushSenderPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PushSenderPort 의 Web Push(VAPID) 구현. 전송 계층 예외(네트워크)는 statusCode 0 으로 돌려 재시도 판정에 맡긴다.
 * VAPID 키가 비어 있으면 기동 시 WARN 을 남기고 keysMissing=true 로 알려 디스패처가 전송을 건너뛴다(3회 실패로 FAILED 가 되지 않게).
 */
@Slf4j
@Component
public class WebPushSenderAdapter implements PushSenderPort {

    private final WebPushClient webPushClient;
    private final boolean keysMissing;

    public WebPushSenderAdapter(WebPushClient webPushClient,
                                @Value("${stagelog.push.vapid.public-key:}") String publicKey,
                                @Value("${stagelog.push.vapid.private-key:}") String privateKey) {
        this.webPushClient = webPushClient;
        this.keysMissing = publicKey.isBlank() || privateKey.isBlank();
        if (keysMissing) {
            log.warn("[웹 푸시 키 미설정] VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY 가 비어 있어 푸시 전송을 건너뜁니다. 배달은 PENDING 으로 남습니다");
        }
    }

    @Override
    public boolean keysMissing() {
        return keysMissing;
    }

    @Override
    public SendResult send(PushSubscriptionData subscription, String payloadJson) {
        try {
            WebPushClient.Result result = webPushClient.send(subscription.endpoint(), subscription.p256dh(), subscription.auth(), payloadJson);
            return new SendResult(result.statusCode(), result.isSuccess() ? null : "HTTP " + result.statusCode() + " " + result.body());
        } catch (RuntimeException e) {
            return SendResult.transportFailure(e);
        }
    }
}
