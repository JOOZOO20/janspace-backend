package com.janspace.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.account.application.Pkce;
import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.account.domain.SocialProvider;
import com.janspace.support.ApiClient;
import com.janspace.support.ApiClient.Res;
import com.janspace.support.Fixtures;
import com.janspace.support.IntegrationTest;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 계약 통합 테스트. 제공자 토큰·프로필은 MockRestServiceServer + 픽스처.
 */
@RecordApplicationEvents
class AuthIntegrationTest extends IntegrationTest {

    static final String NAVER_TOKEN = "https://nid.naver.com/oauth2.0/token";
    static final String NAVER_USERINFO = "https://openapi.naver.com/v1/nid/me";
    static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    static final String GOOGLE_USERINFO = "https://openidconnect.googleapis.com/v1/userinfo";
    static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort
    int port;
    @Autowired
    MockRestServiceServer providers;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplicationEvents events;
    @Autowired
    WebApplicationContext wac;

    ApiClient api;

    @BeforeEach
    void setUp() {
        providers.reset();
        jdbc.update("DELETE FROM user_social_accounts");
        jdbc.update("DELETE FROM users");
        api = new ApiClient(port, objectMapper);
    }

    @Test
    @DisplayName("L1 네이버 n1 로그인: 사용자 1 + 소셜 1, 코드 교환 후 토큰·쿠키 발급, GET /me 가 프로필을 돌려준다")
    void l1_first_login_registers_and_issues_tokens() {
        Session s = login(SocialProvider.NAVER, "naver-userinfo-n1.json");

        assertThat(count("users")).isEqualTo(1);
        assertThat(count("user_social_accounts")).isEqualTo(1);
        assertThat(s.accessToken()).isNotBlank();
        assertThat(s.cookieHeader()).contains("HttpOnly").contains("Path=/api/v1/auth").contains("SameSite=Lax")
                .doesNotContain("Secure");

        Res me = api.get("/api/v1/me", s.accessToken());
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.data().path("id").asLong()).isEqualTo(s.userId());
        assertThat(me.data().path("nickname").asString()).isEqualTo("네이버팬");
        assertThat(me.data().path("email").asString()).isEqualTo("a@x.com");
        assertThat(me.data().path("providers").get(0).asString()).isEqualTo("NAVER");
        assertThat(me.data().path("isAdmin").asBoolean()).isFalse();
        assertThat(me.data().path("consentedAt").isNull()).isTrue();
        assertThat(me.data().path("departure").isNull()).isTrue();
        assertThat(me.data().path("lang").asString()).isEqualTo("ko");
        assertThat(me.data().path("createdAt").asString()).endsWith("Z");
    }

    @Test
    @DisplayName("L2 같은 네이버 계정으로 다시 로그인하면 같은 사용자")
    void l2_repeat_login_returns_same_user() {
        Session first = login(SocialProvider.NAVER, "naver-userinfo-n1.json");
        Session second = login(SocialProvider.NAVER, "naver-userinfo-n1.json");
        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    @DisplayName("L3 같은 이메일(a@x)의 네이버 n1 과 구글 g1 은 서로 다른 사용자 (구글 sub/email/name 매핑 포함)")
    void l3_same_email_different_providers_are_distinct_users() {
        Session naver = login(SocialProvider.NAVER, "naver-userinfo-n1.json");
        Session google = login(SocialProvider.GOOGLE, "google-userinfo-g1.json");
        assertThat(google.userId()).isNotEqualTo(naver.userId());
        assertThat(count("users")).isEqualTo(2);

        Res me = api.get("/api/v1/me", google.accessToken());
        assertThat(me.data().path("nickname").asString()).isEqualTo("Google Fan");
        assertThat(me.data().path("email").asString()).isEqualTo("a@x.com");
        assertThat(me.data().path("providers").get(0).asString()).isEqualTo("GOOGLE");
    }

    @Test
    @DisplayName("L4 계정 연결: 연결 코드 발급 -> complete -> 소셜 2건, 이후 구글 g1 로그인은 U1")
    void l4_link_flow_attaches_and_google_login_resolves_to_u1() {
        Session u1 = loginAndConsent(SocialProvider.NAVER, "naver-userinfo-n1.json");
        String verifier = randomVerifier();
        String linkCode = startLinkAndCallback(u1, SocialProvider.GOOGLE, "google-userinfo-g1.json", verifier);

        Res done = api.post("/api/v1/me/providers/google/complete", u1.accessToken(),
                json("code", linkCode, "codeVerifier", verifier));
        assertThat(done.status()).isEqualTo(200);
        assertThat(done.data().path("providers")).hasSize(2);
        assertThat(count("user_social_accounts")).isEqualTo(2);
        assertThat(count("users")).isEqualTo(1);

        Session viaGoogle = login(SocialProvider.GOOGLE, "google-userinfo-g1.json");
        assertThat(viaGoogle.userId()).isEqualTo(u1.userId());
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    @DisplayName("L5 g1 이 U2 소유일 때 complete -> 409 SOCIAL_ACCOUNT_ALREADY_LINKED, 행 변화 없음")
    void l5_linking_account_owned_by_other_user_conflicts() {
        Session u2 = login(SocialProvider.GOOGLE, "google-userinfo-g1.json");
        Session u1 = loginAndConsent(SocialProvider.NAVER, "naver-userinfo-n1.json");
        String verifier = randomVerifier();
        String linkCode = startLinkAndCallback(u1, SocialProvider.GOOGLE, "google-userinfo-g1.json", verifier);

        Res done = api.post("/api/v1/me/providers/google/complete", u1.accessToken(),
                json("code", linkCode, "codeVerifier", verifier));
        assertThat(done.status()).isEqualTo(409);
        assertThat(done.code()).isEqualTo("SOCIAL_ACCOUNT_ALREADY_LINKED");
        assertThat(count("users")).isEqualTo(2);
        assertThat(count("user_social_accounts")).isEqualTo(2);
        assertThat(u2.userId()).isNotEqualTo(u1.userId());
    }

    @Test
    @DisplayName("L6 잘못된 verifier·재사용·미지의 연결 코드 -> 401 INVALID_AUTH_CODE, 부착 없음")
    void l6_bad_verifier_or_unknown_code_is_401_and_attaches_nothing() {
        Session u1 = loginAndConsent(SocialProvider.NAVER, "naver-userinfo-n1.json");
        String verifier = randomVerifier();
        String linkCode = startLinkAndCallback(u1, SocialProvider.GOOGLE, "google-userinfo-g1.json", verifier);

        Res wrong = api.post("/api/v1/me/providers/google/complete", u1.accessToken(),
                json("code", linkCode, "codeVerifier", randomVerifier()));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("INVALID_AUTH_CODE");
        // 실패한 코드는 폐기됐으므로 올바른 verifier 로도 다시 쓸 수 없다
        Res reuse = api.post("/api/v1/me/providers/google/complete", u1.accessToken(),
                json("code", linkCode, "codeVerifier", verifier));
        assertThat(reuse.status()).isEqualTo(401);
        Res unknown = api.post("/api/v1/me/providers/google/complete", u1.accessToken(),
                json("code", "no-such-code", "codeVerifier", verifier));
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(count("user_social_accounts")).isEqualTo(1);

        // 로그인 코드도 같다: verifier 불일치 -> 401, 폐기되어 재시도도 401
        String loginVerifier = randomVerifier();
        String code = callback(SocialProvider.GOOGLE, authorize(SocialProvider.GOOGLE, Pkce.challengeOf(loginVerifier)),
                "google-userinfo-g1.json", "/auth/callback");
        Res bad = api.post("/api/v1/auth/token", null, json("code", code, "codeVerifier", randomVerifier()));
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.code()).isEqualTo("INVALID_AUTH_CODE");
        Res again = api.post("/api/v1/auth/token", null, json("code", code, "codeVerifier", loginVerifier));
        assertThat(again.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("L7 U1 이 로그인한 상태여도 login 모드 구글 콜백은 g1 의 새 사용자를 만들고 U1 에 붙이지 않는다")
    void l7_login_mode_never_attaches_to_current_user() {
        Session u1 = loginAndConsent(SocialProvider.NAVER, "naver-userinfo-n1.json");
        Session g1 = login(SocialProvider.GOOGLE, "google-userinfo-g1.json");
        assertThat(g1.userId()).isNotEqualTo(u1.userId());
        assertThat(count("users")).isEqualTo(2);
        Res me = api.get("/api/v1/me", u1.accessToken());
        assertThat(me.data().path("providers")).hasSize(1);
    }

    @Test
    @DisplayName("L8 이미 네이버가 연결된 사용자의 네이버 연결 시작 -> 409 PROVIDER_ALREADY_LINKED")
    void l8_second_account_of_same_provider_conflicts() {
        Session u1 = loginAndConsent(SocialProvider.NAVER, "naver-userinfo-n1.json");
        Res start = api.post("/api/v1/me/providers/naver", u1.accessToken(),
                json("codeChallenge", Pkce.challengeOf(randomVerifier())));
        assertThat(start.status()).isEqualTo(409);
        assertThat(start.code()).isEqualTo("PROVIDER_ALREADY_LINKED");
        assertThat(count("user_social_accounts")).isEqualTo(1);
    }

    @Test
    @DisplayName("L9 탈퇴: deleted_at 기록, 소셜 행 삭제, UserWithdrawn 발행, 다음 n1 로그인은 새 id")
    void l9_withdraw_soft_deletes_and_next_login_is_new_user() throws Exception {
        Session u1 = loginAndConsent(SocialProvider.NAVER, "naver-userinfo-n1.json");

        // 이벤트 기록은 테스트 스레드에 묶이므로 탈퇴만 MockMvc 로 같은 스레드에서 호출한다
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        mockMvc.perform(delete("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + u1.accessToken()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?", Object.class, u1.userId())).isNotNull();
        assertThat(count("user_social_accounts")).isZero();
        assertThat(events.stream(UserWithdrawn.class).map(UserWithdrawn::userId)).containsExactly(u1.userId());
        assertThat(api.call(HttpMethod.POST, "/api/v1/auth/refresh", null, u1.refreshToken(), null).status()).isEqualTo(401);

        Session again = login(SocialProvider.NAVER, "naver-userinfo-n1.json");
        assertThat(again.userId()).isNotEqualTo(u1.userId());
        assertThat(count("users")).isEqualTo(2);
    }

    @Test
    @DisplayName("L10 로그아웃 후 옛 쿠키로 refresh -> 401. 회전 후 옛 토큰 재사용 -> 가족 폐기, 새 토큰도 거부")
    void l10_logout_and_rotation_reuse_detection() {
        Session s = login(SocialProvider.NAVER, "naver-userinfo-n1.json");
        Res logout = api.call(HttpMethod.POST, "/api/v1/auth/logout", null, s.refreshToken(), null);
        assertThat(logout.status()).isEqualTo(200);
        assertThat(logout.setCookie()).contains("refresh_token=;").contains("Max-Age=0");
        Res afterLogout = api.call(HttpMethod.POST, "/api/v1/auth/refresh", null, s.refreshToken(), null);
        assertThat(afterLogout.status()).isEqualTo(401);
        assertThat(afterLogout.code()).isEqualTo("INVALID_REFRESH_TOKEN");

        Session t = login(SocialProvider.NAVER, "naver-userinfo-n1.json");
        Res rotated = api.call(HttpMethod.POST, "/api/v1/auth/refresh", null, t.refreshToken(), null);
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(rotated.data().path("accessToken").asString()).isNotBlank();
        String newRefresh = rotated.refreshCookie();
        assertThat(newRefresh).isNotBlank().isNotEqualTo(t.refreshToken());

        Res reuse = api.call(HttpMethod.POST, "/api/v1/auth/refresh", null, t.refreshToken(), null);
        assertThat(reuse.status()).isEqualTo(401);
        Res revoked = api.call(HttpMethod.POST, "/api/v1/auth/refresh", null, newRefresh, null);
        assertThat(revoked.status()).isEqualTo(401);
        assertThat(revoked.code()).isEqualTo("INVALID_REFRESH_TOKEN");
        assertThat(api.call(HttpMethod.POST, "/api/v1/auth/refresh", null, null, null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("L11 Bearer 로 /me 조회, 토큰 없으면 401, 비관리자의 /admin 은 403 봉투, 관리자는 200")
    void l11_bearer_resolution_and_admin_authority() {
        Session s = login(SocialProvider.NAVER, "naver-userinfo-n1.json");
        assertThat(api.get("/api/v1/me", s.accessToken()).data().path("id").asLong()).isEqualTo(s.userId());

        Res anonymous = api.get("/api/v1/me", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.code()).isEqualTo("UNAUTHORIZED");
        Res garbage = api.get("/api/v1/me", "not.a.token");
        assertThat(garbage.status()).isEqualTo(401);

        Res forbidden = api.get("/api/v1/admin/ping", s.accessToken());
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.code()).isEqualTo("FORBIDDEN");
        assertThat(api.get("/api/v1/admin/ping", null).status()).isEqualTo(401);

        jdbc.update("UPDATE users SET is_admin = TRUE WHERE id = ?", s.userId());
        Session admin = login(SocialProvider.NAVER, "naver-userinfo-n1.json");
        assertThat(api.get("/api/v1/admin/ping", admin.accessToken()).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/me", admin.accessToken()).data().path("isAdmin").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("L12 동의 전 PATCH /me -> 403 CONSENT_REQUIRED, 동의 후 200. lang·departure 반영, 미지원 lang 400")
    void l12_consent_gate_and_profile_update() {
        Session s = login(SocialProvider.NAVER, "naver-userinfo-n1.json");
        Res blocked = api.call(HttpMethod.PATCH, "/api/v1/me", s.accessToken(), null, json("nickname", "새이름"));
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.code()).isEqualTo("CONSENT_REQUIRED");

        Res consent = api.post("/api/v1/me/consent", s.accessToken(), json("termsVersion", "2026-09-01"));
        assertThat(consent.status()).isEqualTo(200);
        assertThat(consent.data().path("consentedAt").asString()).endsWith("Z");

        Res updated = api.call(HttpMethod.PATCH, "/api/v1/me", s.accessToken(), null, """
                {"nickname":"새이름","lang":"ja","departure":{"name":"서울 노원구","countryCode":"KR","lat":37.654305,"lng":127.056297}}
                """);
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.data().path("nickname").asString()).isEqualTo("새이름");
        assertThat(updated.data().path("lang").asString()).isEqualTo("ja");
        assertThat(updated.data().path("departure").path("name").asString()).isEqualTo("서울 노원구");
        assertThat(updated.data().path("departure").path("lat").asDouble()).isEqualTo(37.654305);

        Res me = api.get("/api/v1/me", s.accessToken());
        assertThat(me.data().path("departure").path("countryCode").asString()).isEqualTo("KR");
        assertThat(me.data().path("lang").asString()).isEqualTo("ja");

        Res badLang = api.call(HttpMethod.PATCH, "/api/v1/me", s.accessToken(), null, json("lang", "xx"));
        assertThat(badLang.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("일회용 코드: 두 번째 교환은 401")
    void auth_code_is_single_use() {
        String verifier = randomVerifier();
        String code = callback(SocialProvider.NAVER, authorize(SocialProvider.NAVER, Pkce.challengeOf(verifier)),
                "naver-userinfo-n1.json", "/auth/callback");
        assertThat(api.post("/api/v1/auth/token", null, json("code", code, "codeVerifier", verifier)).status()).isEqualTo(200);
        Res second = api.post("/api/v1/auth/token", null, json("code", code, "codeVerifier", verifier));
        assertThat(second.status()).isEqualTo(401);
        assertThat(second.code()).isEqualTo("INVALID_AUTH_CODE");
    }

    @Test
    @DisplayName("authorize: challenge 없음·plain 방식 -> 400 INVALID_PKCE, 미지의 제공자 -> 400 UNSUPPORTED_PROVIDER")
    void authorize_validation() {
        Res noChallenge = api.get("/api/v1/auth/naver/authorize?code_challenge_method=S256&mode=login", null);
        assertThat(noChallenge.status()).isEqualTo(400);
        assertThat(noChallenge.code()).isEqualTo("INVALID_PKCE");
        Res plain = api.get("/api/v1/auth/naver/authorize?code_challenge=" + Pkce.challengeOf(randomVerifier())
                + "&code_challenge_method=plain&mode=login", null);
        assertThat(plain.status()).isEqualTo(400);
        Res unknown = api.get("/api/v1/auth/kakao/authorize?code_challenge=" + Pkce.challengeOf(randomVerifier())
                + "&code_challenge_method=S256&mode=login", null);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.code()).isEqualTo("UNSUPPORTED_PROVIDER");
    }

    @Test
    @DisplayName("callback: 제공자 오류·토큰 교환 실패·미지의 state 는 프론트로 error 리다이렉트, 사용자 생성 없음")
    void callback_failures_redirect_with_error() {
        Res denied = api.get("/api/v1/auth/naver/callback?error=access_denied&state=x", null);
        assertThat(denied.status()).isEqualTo(302);
        assertThat(denied.location()).isEqualTo("http://localhost:3000/auth/callback?error=OAUTH_PROVIDER_ERROR");

        String state = authorize(SocialProvider.NAVER, Pkce.challengeOf(randomVerifier()));
        providers.expect(requestTo(NAVER_TOKEN)).andRespond(withServerError());
        Res failed = api.get("/api/v1/auth/naver/callback?code=c&state=" + state, null);
        assertThat(failed.status()).isEqualTo(302);
        assertThat(failed.location()).endsWith("/auth/callback?error=OAUTH_PROVIDER_ERROR");

        Res badState = api.get("/api/v1/auth/naver/callback?code=c&state=unknown-state", null);
        assertThat(badState.status()).isEqualTo(302);
        assertThat(badState.location()).endsWith("/auth/callback?error=INVALID_AUTH_CODE");
        assertThat(count("users")).isZero();
    }

    // --- 흐름 헬퍼 ---

    record Session(long userId, String accessToken, String refreshToken, String cookieHeader) {
    }

    Session login(SocialProvider provider, String userinfoFixture) {
        String verifier = randomVerifier();
        String state = authorize(provider, Pkce.challengeOf(verifier));
        String code = callback(provider, state, userinfoFixture, "/auth/callback");
        Res token = api.post("/api/v1/auth/token", null, json("code", code, "codeVerifier", verifier));
        assertThat(token.status()).as("token exchange: %s", token.body()).isEqualTo(200);
        return new Session(token.data().path("user").path("id").asLong(), token.data().path("accessToken").asString(),
                token.refreshCookie(), token.setCookie());
    }

    Session loginAndConsent(SocialProvider provider, String userinfoFixture) {
        Session s = login(provider, userinfoFixture);
        assertThat(api.post("/api/v1/me/consent", s.accessToken(), json("termsVersion", "2026-09-01")).status()).isEqualTo(200);
        return s;
    }

    // GET authorize -> 302 제공자 -> Location 의 state
    String authorize(SocialProvider provider, String challenge) {
        Res res = api.get("/api/v1/auth/" + provider.path() + "/authorize?code_challenge=" + challenge
                + "&code_challenge_method=S256&mode=login", null);
        assertThat(res.status()).isEqualTo(302);
        String location = res.location();
        assertThat(location).startsWith(provider == SocialProvider.NAVER
                ? "https://nid.naver.com/oauth2.0/authorize?" : "https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(location).contains("redirect_uri=http%3A%2F%2Flocalhost%3A" + port + "%2Fapi%2Fv1%2Fauth%2F"
                + provider.path() + "%2Fcallback");
        return queryParam(location, "state");
    }

    // 제공자 토큰·프로필을 픽스처로 응답하게 하고 콜백 -> 302 프론트 -> Location 의 code
    String callback(SocialProvider provider, String state, String userinfoFixture, String expectedPage) {
        expectProvider(provider, userinfoFixture);
        Res res = api.get("/api/v1/auth/" + provider.path() + "/callback?code=provider-code&state=" + state, null);
        assertThat(res.status()).isEqualTo(302);
        assertThat(res.location()).startsWith("http://localhost:3000" + expectedPage + "?code=");
        providers.verify();
        providers.reset();
        return queryParam(res.location(), "code");
    }

    String startLinkAndCallback(Session user, SocialProvider provider, String userinfoFixture, String verifier) {
        Res start = api.post("/api/v1/me/providers/" + provider.path(), user.accessToken(),
                json("codeChallenge", Pkce.challengeOf(verifier)));
        assertThat(start.status()).as("link start: %s", start.body()).isEqualTo(200);
        String state = queryParam(start.data().path("authorizeUrl").asString(), "state");
        return callback(provider, state, userinfoFixture, "/settings/link-callback");
    }

    void expectProvider(SocialProvider provider, String userinfoFixture) {
        if (provider == SocialProvider.NAVER) {
            providers.expect(requestTo(NAVER_TOKEN))
                    .andRespond(withSuccess(Fixtures.read("oauth/naver-token.json"), MediaType.APPLICATION_JSON));
            providers.expect(requestTo(NAVER_USERINFO))
                    .andRespond(withSuccess(Fixtures.read("oauth/" + userinfoFixture), MediaType.APPLICATION_JSON));
        } else {
            providers.expect(requestTo(GOOGLE_TOKEN))
                    .andRespond(withSuccess(Fixtures.read("oauth/google-token.json"), MediaType.APPLICATION_JSON));
            providers.expect(requestTo(GOOGLE_USERINFO))
                    .andRespond(withSuccess(Fixtures.read("oauth/" + userinfoFixture), MediaType.APPLICATION_JSON));
        }
    }

    static String queryParam(String url, String name) {
        String value = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
        assertThat(value).as("query param %s in %s", name, url).isNotBlank();
        return value;
    }

    static String randomVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String json(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(kv[i]).append("\":\"").append(kv[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
