package com.janspace.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.account.application.Pkce;
import com.janspace.attendance.support.AttendanceIntegrationTest;
import com.janspace.notification.application.port.PushSenderPort;
import com.janspace.support.Fixtures;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 기능 간 E2E 시나리오 기반. 모든 단계를 HTTP(MockMvc + 실제 JWT)로 밟는다. 로그인은 제공자 픽스처(MockRestServiceServer)로 PKCE 흐름을 그대로 탄다.
 * attendance 기반(users·attendances·event 테이블 정리)에 더해 setlist·photocard·catalog·소셜·sync_runs·push 를 전후로 비운다.
 * 웹 푸시 전송(PushSenderPort)은 목이라 실제 푸시 서비스로 나가지 않는다.
 */
public abstract class E2eIntegrationTest extends AttendanceIntegrationTest {

    static final String NAVER_TOKEN = "https://nid.naver.com/oauth2.0/token";
    static final String NAVER_USERINFO = "https://openapi.naver.com/v1/nid/me";
    static final SecureRandom RANDOM = new SecureRandom();
    static final List<String> EXTRA_TABLES = List.of("photocards", "setlist_unmatched_names", "setlist_revisions", "setlist_items",
            "setlist_pages", "song_aliases", "song_translations", "songs", "user_social_accounts", "sync_runs",
            "push_deliveries", "push_jobs", "push_subscriptions");

    @Autowired
    protected MockRestServiceServer providers;

    @MockitoBean
    protected PushSenderPort sender;

    /** 로그인 결과: 사용자 ID 와 액세스 토큰. */
    protected record Session(long userId, String accessToken) {
    }

    @BeforeEach
    void cleanE2eTables() {
        providers.reset();
        truncateExtra();
    }

    @AfterEach
    void cleanE2eTablesAfter() {
        truncateExtra();
    }

    private void truncateExtra() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        EXTRA_TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    // --- 인증 흐름: authorize 302 -> 제공자 픽스처 -> callback 302 -> POST /auth/token ---

    protected Session login(String userinfoFixture) throws Exception {
        String verifier = randomVerifier();
        MvcResult authorize = mvc.perform(get("/api/v1/auth/naver/authorize")
                        .param("code_challenge", Pkce.challengeOf(verifier))
                        .param("code_challenge_method", "S256").param("mode", "login"))
                .andExpect(status().isFound()).andReturn();
        String state = queryParam(authorize.getResponse().getHeader(HttpHeaders.LOCATION), "state");

        providers.expect(requestTo(NAVER_TOKEN))
                .andRespond(withSuccess(Fixtures.read("oauth/naver-token.json"), MediaType.APPLICATION_JSON));
        providers.expect(requestTo(NAVER_USERINFO))
                .andRespond(withSuccess(Fixtures.read("oauth/" + userinfoFixture), MediaType.APPLICATION_JSON));
        MvcResult callback = mvc.perform(get("/api/v1/auth/naver/callback").param("code", "provider-code").param("state", state))
                .andExpect(status().isFound()).andReturn();
        String location = callback.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith("http://localhost:3000/auth/callback?code=");
        providers.verify();
        providers.reset();

        DocumentContext token = json(mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + queryParam(location, "code") + "\",\"codeVerifier\":\"" + verifier + "\"}"))
                .andExpect(status().isOk()).andReturn());
        return new Session(token.read("$.data.user.id", Long.class), token.read("$.data.accessToken", String.class));
    }

    protected Session loginAndConsent(String userinfoFixture) throws Exception {
        Session s = login(userinfoFixture);
        mvc.perform(post("/api/v1/me/consent").with(bearer(s)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"termsVersion\":\"2026-09-01\"}")).andExpect(status().isOk());
        return s;
    }

    // 첫 관리자 만들기: SQL 로 플래그를 켜고 다시 로그인해야 adm 클레임이 실린다
    protected Session loginAsAdmin(String userinfoFixture) throws Exception {
        Session first = login(userinfoFixture);
        jdbc.update("UPDATE users SET is_admin = TRUE WHERE id = ?", first.userId());
        Session admin = loginAndConsent(userinfoFixture);
        assertThat(admin.userId()).isEqualTo(first.userId());
        return admin;
    }

    protected static org.springframework.test.web.servlet.request.RequestPostProcessor bearer(Session s) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + s.accessToken());
            return request;
        };
    }

    protected static DocumentContext json(MvcResult result) throws Exception {
        return JsonPath.parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    static String randomVerifier() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String queryParam(String url, String name) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst(name);
    }
}
