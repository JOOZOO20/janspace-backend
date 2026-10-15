package com.janspace.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.account.application.AccessTokenService;
import com.janspace.support.ApiClient;
import com.janspace.support.ApiClient.Res;
import com.janspace.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 소셜 계정 연결 해제를 실제 HTTP 와 MySQL 로 검증한다.
 */
class ProfileIntegrationTest extends IntegrationTest {

    static final long U1 = 11L;
    static final long U2 = 12L;

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
}
