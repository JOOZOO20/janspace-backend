package com.janspace.integration.webpush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;
import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 실행 중 만든 임시 VAPID 키 쌍으로 요청을 만들고 MockRestServiceServer 로 헤더·상태 매핑을 검증한다. 실키는 쓰지 않는다.
 */
class WebPushClientTest {

    static final String ENDPOINT = "https://push.example.com/send/abc123";

    static KeyPair server;
    static String p256dh;
    static String auth;

    @BeforeAll
    static void keys() throws Exception {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance(Utils.ALGORITHM, BouncyCastleProvider.PROVIDER_NAME);
        generator.initialize(ECNamedCurveTable.getParameterSpec(Utils.CURVE), new SecureRandom());
        server = generator.generateKeyPair();
        KeyPair client = generator.generateKeyPair();
        p256dh = Base64.getUrlEncoder().withoutPadding().encodeToString(Utils.encode((ECPublicKey) client.getPublic()));
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        auth = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    @Test
    @DisplayName("aes128gcm 암호화 + VAPID Authorization 헤더로 POST 하고 상태 코드를 그대로 돌려준다 (201, 410)")
    void builds_vapid_request_and_maps_status() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mock = MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo(ENDPOINT)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", startsWith("vapid t=")))
                .andExpect(header("Content-Encoding", "aes128gcm"))
                .andExpect(header("TTL", String.valueOf(WebPushClient.TTL_SECONDS)))
                .andRespond(withStatus(HttpStatus.CREATED));
        mock.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.GONE).body("gone"));

        WebPushClient client = new WebPushClient(builder, server, "mailto:ops@example.com");
        WebPushClient.Result created = client.send(ENDPOINT, p256dh, auth, "{\"kind\":\"RECORD_REMINDER\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.isSuccess()).isTrue();

        WebPushClient.Result gone = client.send(ENDPOINT, p256dh, auth, "{}");
        assertThat(gone.statusCode()).isEqualTo(410);
        assertThat(gone.body()).isEqualTo("gone");
        mock.verify();
    }

    @Test
    @DisplayName("키가 비어 있으면 요청을 만들지 않고 IllegalStateException, 잘못된 키 문자열은 생성 시 실패")
    void missing_or_invalid_keys() {
        WebPushClient noKeys = new WebPushClient(RestClient.builder(), "", "", "");
        assertThatThrownBy(() -> noKeys.send(ENDPOINT, p256dh, auth, "{}")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebPushClient(RestClient.builder(), "not-a-key", "nope", ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
