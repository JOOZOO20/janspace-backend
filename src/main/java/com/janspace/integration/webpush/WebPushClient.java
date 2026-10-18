package com.janspace.integration.webpush;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Security;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.AbstractPushService;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.HttpRequest;
import nl.martijndwars.webpush.Notification;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jose4j.lang.JoseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Web Push 클라이언트. 페이로드 암호화(aes128gcm)와 VAPID 서명은 web-push 라이브러리가 만들고, 전송은 RestClient 가 한다.
 * 키(janspace.push.vapid.*)가 비어 있으면 요청을 만들 수 없어 send 가 IllegalStateException 을 던진다.
 * 응답 상태는 예외 없이 그대로 돌려주며(4xx·5xx 포함), 네트워크 실패만 RestClient 예외(RuntimeException)다.
 */
@Slf4j
@Component
public class WebPushClient {

    public record Result(int statusCode, String body) {

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    // TTL(초). 기록 알림은 하루 안에 의미가 있으므로 하루로 둔다
    static final int TTL_SECONDS = 24 * 60 * 60;

    private final RestClient restClient;
    private final VapidRequests vapid;

    @Autowired
    public WebPushClient(RestClient.Builder restClientBuilder,
                         @Value("${janspace.push.vapid.public-key:}") String publicKey,
                         @Value("${janspace.push.vapid.private-key:}") String privateKey,
                         @Value("${janspace.push.vapid.subject:}") String subject) {
        this(restClientBuilder, keysConfigured(publicKey, privateKey) ? VapidRequests.of(publicKey, privateKey, subject) : null);
        if (vapid == null) {
            log.info("[웹 푸시 키 미설정] VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY 가 비어 있어 발송이 전부 실패로 기록됩니다");
        }
    }

    // 테스트·키 생성용: 이미 만든 키 쌍으로 구성
    public WebPushClient(RestClient.Builder restClientBuilder, KeyPair keyPair, String subject) {
        this(restClientBuilder, new VapidRequests(keyPair, subject));
    }

    private WebPushClient(RestClient.Builder restClientBuilder, VapidRequests vapid) {
        this.restClient = restClientBuilder.build();
        this.vapid = vapid;
    }

    public Result send(String endpoint, String p256dh, String auth, String payloadJson) {
        if (vapid == null) {
            throw new IllegalStateException("VAPID 키가 설정되지 않았습니다");
        }
        // 1. 암호화 + VAPID 헤더 (Authorization: vapid t=..., k=...)
        HttpRequest request;
        try {
            Notification notification = new Notification(endpoint, p256dh, auth, payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), TTL_SECONDS);
            request = vapid.request(notification);
        } catch (GeneralSecurityException | java.io.IOException | JoseException e) {
            throw new IllegalStateException("웹 푸시 요청을 만들 수 없습니다: " + e.getMessage(), e);
        }
        // 2. 전송. 상태 코드는 판정하지 않고 호출자(notification)에게 넘긴다
        return restClient.post()
                .uri(request.getUrl())
                .headers(h -> request.getHeaders().forEach(h::set))
                .body(request.getBody())
                .exchange((req, res) -> new Result(res.getStatusCode().value(), res.bodyTo(String.class)));
    }

    private static boolean keysConfigured(String publicKey, String privateKey) {
        return publicKey != null && !publicKey.isBlank() && privateKey != null && !privateKey.isBlank();
    }

    // 라이브러리의 요청 생성(prepareRequest)만 쓴다. 라이브러리의 Apache 비동기 클라이언트는 의존성에서 뺐다
    static final class VapidRequests extends AbstractPushService<VapidRequests> {

        static {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
        }

        VapidRequests(KeyPair keyPair, String subject) {
            super(keyPair, subject == null || subject.isBlank() ? null : subject);
        }

        private VapidRequests(String publicKey, String privateKey, String subject) throws GeneralSecurityException {
            super(publicKey, privateKey, subject == null || subject.isBlank() ? null : subject);
        }

        static VapidRequests of(String publicKey, String privateKey, String subject) {
            try {
                return new VapidRequests(publicKey, privateKey, subject);
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalStateException("VAPID 키를 읽을 수 없습니다 (base64url P-256 인지 확인)", e);
            }
        }

        HttpRequest request(Notification notification) throws GeneralSecurityException, java.io.IOException, JoseException {
            return prepareRequest(notification, Encoding.AES128GCM);
        }
    }
}
