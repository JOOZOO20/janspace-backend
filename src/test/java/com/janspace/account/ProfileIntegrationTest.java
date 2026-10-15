package com.janspace.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.janspace.account.application.AccessTokenService;
import com.janspace.support.ApiClient;
import com.janspace.support.ApiClient.Res;
import com.janspace.support.IntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 소셜 계정 연결 해제와 프로필 사진 업로드·교체·삭제·탈퇴 정리를 실제 HTTP(Tomcat 의 PUT multipart 포함)와 MySQL 로 검증한다.
 * S3 는 IntegrationTest 의 S3Client 목이고, CDN 주소가 비어 있어 avatarUrl 은 "/" + 키다.
 */
class ProfileIntegrationTest extends IntegrationTest {

    static final long U1 = 11L;
    static final long U2 = 12L;
    static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AccessTokenService accessTokenService;

    ApiClient api;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM user_social_accounts");
        jdbc.update("DELETE FROM users");
        api = new ApiClient(port, objectMapper);
        insertUser(U1);
        insertUser(U2);
    }

    @Test
    @DisplayName("연결 해제: 내 구글만 지워지고(남의 구글 유지) providers=[NAVER], 다시 해제 404, 마지막 네이버 409, 미지의 제공자 400, 비로그인 401")
    void unlink_rules() {
        social(U1, "NAVER", "n1");
        social(U1, "GOOGLE", "g1");
        social(U2, "GOOGLE", "g2");
        String token = accessTokenService.issue(U1, false);

        Res done = api.call(HttpMethod.DELETE, "/api/v1/me/providers/google", token, null, null);
        assertThat(done.status()).as(done.body()).isEqualTo(200);
        assertThat(done.data().path("providers")).hasSize(1);
        assertThat(done.data().path("providers").get(0).asString()).isEqualTo("NAVER");
        assertThat(providerUserIds()).containsExactlyInAnyOrder("n1", "g2");

        Res again = api.call(HttpMethod.DELETE, "/api/v1/me/providers/google", token, null, null);
        assertThat(again.status()).isEqualTo(404);
        assertThat(again.code()).isEqualTo("PROVIDER_NOT_LINKED");

        Res last = api.call(HttpMethod.DELETE, "/api/v1/me/providers/naver", token, null, null);
        assertThat(last.status()).isEqualTo(409);
        assertThat(last.code()).isEqualTo("CANNOT_UNLINK_LAST_PROVIDER");
        assertThat(providerUserIds()).containsExactlyInAnyOrder("n1", "g2");

        Res unknown = api.call(HttpMethod.DELETE, "/api/v1/me/providers/kakao", token, null, null);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.code()).isEqualTo("UNSUPPORTED_PROVIDER");
        assertThat(api.call(HttpMethod.DELETE, "/api/v1/me/providers/naver", null, null, null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("연결 해제 동시 요청: 네이버·구글을 동시에 해제해도 하나만 성공하고 로그인 수단 하나가 남는다")
    void concurrent_unlink_keeps_one_provider() throws Exception {
        String token = accessTokenService.issue(U1, false);
        for (int round = 0; round < 3; round++) {
            jdbc.update("DELETE FROM user_social_accounts WHERE user_id = ?", U1);
            social(U1, "NAVER", "n1");
            social(U1, "GOOGLE", "g1");
            CountDownLatch start = new CountDownLatch(1);
            List<Integer> statuses;
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                List<Future<Integer>> calls = new ArrayList<>();
                for (String provider : List.of("naver", "google")) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        return api.call(HttpMethod.DELETE, "/api/v1/me/providers/" + provider, token, null, null).status();
                    }));
                }
                start.countDown();
                statuses = new ArrayList<>();
                for (Future<Integer> call : calls) {
                    statuses.add(call.get());
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_social_accounts WHERE user_id = ?", Integer.class, U1))
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("프로필 사진: 처음엔 avatarUrl null, PUT 으로 avatars/{userId}/{uuid}.png 업로드, JPEG 로 교체하면 이전 객체 삭제, DELETE 로 null·객체 삭제, 사진 없이 DELETE 도 200")
    void avatar_upload_replace_delete() throws IOException {
        String token = accessTokenService.issue(U1, false);
        assertThat(api.get("/api/v1/me", token).data().path("avatarUrl").isNull()).isTrue();

        // 1. 업로드
        Res first = putAvatar(token, image(64, 64, "png"), "a.png");
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        String firstKey = avatarKey(U1);
        assertThat(firstKey).matches("avatars/" + U1 + "/" + UUID_RE + "\\.png");
        assertThat(first.data().path("avatarUrl").asString()).isEqualTo("/" + firstKey);
        assertThat(api.get("/api/v1/me", token).data().path("avatarUrl").asString()).isEqualTo("/" + firstKey);
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().key()).isEqualTo(firstKey);
        assertThat(put.getValue().contentType()).isEqualTo("image/png");
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

        // 2. 교체: 새 키, 이전 객체 삭제
        Res second = putAvatar(token, image(64, 64, "jpg"), "b.jpg");
        assertThat(second.status()).isEqualTo(200);
        String secondKey = avatarKey(U1);
        assertThat(secondKey).matches("avatars/" + U1 + "/" + UUID_RE + "\\.jpg").isNotEqualTo(firstKey);
        assertThat(second.data().path("avatarUrl").asString()).isEqualTo("/" + secondKey);
        assertThat(deletedKeys(1)).containsExactly(firstKey);

        // 3. 삭제: null, 객체 삭제. 다시 삭제해도 200 이고 더 지우지 않는다
        Res deleted = api.call(HttpMethod.DELETE, "/api/v1/me/avatar", token, null, null);
        assertThat(deleted.status()).isEqualTo(200);
        assertThat(deleted.data().path("avatarUrl").isNull()).isTrue();
        assertThat(avatarKey(U1)).isNull();
        assertThat(deletedKeys(2)).containsExactly(firstKey, secondKey);
        assertThat(api.call(HttpMethod.DELETE, "/api/v1/me/avatar", token, null, null).status()).isEqualTo(200);
        deletedKeys(2);
    }

    @Test
    @DisplayName("프로필 사진 거부: 이미지가 아님·긴 변 1025px 는 400 AVATAR_IMAGE_INVALID 이고 업로드하지 않는다. 비로그인 401")
    void avatar_validation() throws IOException {
        String token = accessTokenService.issue(U1, false);
        Res text = putAvatar(token, "not an image".getBytes(), "a.png");
        assertThat(text.status()).isEqualTo(400);
        assertThat(text.code()).isEqualTo("AVATAR_IMAGE_INVALID");
        Res large = putAvatar(token, image(1025, 10, "png"), "big.png");
        assertThat(large.status()).isEqualTo(400);
        assertThat(large.code()).isEqualTo("AVATAR_IMAGE_INVALID");
        assertThat(putAvatar(null, image(10, 10, "png"), "a.png").status()).isEqualTo(401);

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(avatarKey(U1)).isNull();
    }

    @Test
    @DisplayName("이전 객체 삭제가 실패해도 교체는 성공하고 새 키가 저장된다")
    void avatar_cleanup_failure_does_not_fail_request() throws IOException {
        String token = accessTokenService.issue(U1, false);
        assertThat(putAvatar(token, image(10, 10, "png"), "a.png").status()).isEqualTo(200);
        String firstKey = avatarKey(U1);
        doThrow(SdkClientException.create("S3 down")).when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        Res replaced = putAvatar(token, image(10, 10, "png"), "b.png");
        assertThat(replaced.status()).isEqualTo(200);
        assertThat(avatarKey(U1)).isNotNull().isNotEqualTo(firstKey);
        assertThat(deletedKeys(1)).containsExactly(firstKey);
    }

    @Test
    @DisplayName("탈퇴하면 avatar_key 가 지워지고 S3 객체도 삭제된다")
    void withdraw_deletes_avatar() throws IOException {
        String token = accessTokenService.issue(U1, false);
        assertThat(putAvatar(token, image(10, 10, "png"), "a.png").status()).isEqualTo(200);
        String key = avatarKey(U1);

        assertThat(api.call(HttpMethod.DELETE, "/api/v1/me", token, null, null).status()).isEqualTo(200);
        assertThat(avatarKey(U1)).isNull();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Object.class, U1)).isNotNull();
        assertThat(deletedKeys(1)).containsExactly(key);
    }

    // multipart PUT 을 실제 Tomcat 으로 보낸다 (MockMvc 는 서블릿 컨테이너의 multipart 파싱을 거치지 않는다)
    Res putAvatar(String token, byte[] bytes, String filename) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("image", bytes).filename(filename)
                .contentType(filename.endsWith(".jpg") ? MediaType.IMAGE_JPEG : MediaType.IMAGE_PNG);
        RestClient.RequestBodySpec spec = RestClient.create("http://localhost:" + port).put().uri("/api/v1/me/avatar")
                .contentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return spec.body(body.build()).exchange((req, res) -> {
            String text = res.bodyTo(String.class);
            JsonNode json = text == null || text.isBlank() ? null : objectMapper.readTree(text);
            return new Res(res.getStatusCode().value(), res.getHeaders(), text, json);
        });
    }

    // 지금까지 deleteObject 로 지운 키 (호출 횟수도 함께 확인)
    List<String> deletedKeys(int expectedCalls) {
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(expectedCalls)).deleteObject(captor.capture());
        return captor.getAllValues().stream().map(DeleteObjectRequest::key).toList();
    }

    void insertUser(long id) {
        jdbc.update("INSERT INTO users (id, nickname, locale, is_admin, consented_at, terms_version, created_at, updated_at) "
                + "VALUES (?, ?, 'ko', FALSE, UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", id, "user" + id);
    }

    void social(long userId, String provider, String providerUserId) {
        jdbc.update("INSERT INTO user_social_accounts (user_id, provider, provider_user_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", userId, provider, providerUserId);
    }

    List<String> providerUserIds() {
        return jdbc.queryForList("SELECT provider_user_id FROM user_social_accounts", String.class);
    }

    String avatarKey(long userId) {
        return jdbc.queryForObject("SELECT avatar_key FROM users WHERE id = ?", String.class, userId);
    }

    // 실제 인코딩된 이미지 바이트. jpg 는 알파 채널이 없어야 ImageIO 가 쓴다
    static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage img = new BufferedImage(width, height, "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }
}
