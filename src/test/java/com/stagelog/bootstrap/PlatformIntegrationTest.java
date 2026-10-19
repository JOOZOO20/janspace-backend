package com.stagelog.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.account.application.AccessTokenService;
import com.stagelog.support.IntegrationTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 공통 웹 기반 통합 테스트: CORS, 요청 ID·진입/종료 로그, 동의 게이트(403, 탈퇴 예외), 실제 경로의 오류 봉투, Swagger 문서 구성.
 * Swagger 문서를 확인하려고 이 클래스만 api-docs 를 켠다 (test 프로파일 기본값은 꺼짐).
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class PlatformIntegrationTest extends IntegrationTest {

    static final String FRONT = "http://localhost:3000";
    static final long USER_ID = 9_101L;

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AccessTokenService tokens;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    RequestMappingHandlerAdapter handlerAdapter;

    final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void removeUser() {
        jdbc.update("DELETE FROM users WHERE id = ?", USER_ID);
    }

    @Test
    @DisplayName("프론트 출처 preflight 는 토큰 없이 200 이고 credentials 를 허용한다")
    void preflight_from_front_is_allowed_without_token() throws Exception {
        HttpResponse<String> res = send("OPTIONS", "/api/v1/me", null,
                "Origin", FRONT, "Access-Control-Request-Method", "PATCH",
                "Access-Control-Request-Headers", "authorization,content-type");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(header(res, "Access-Control-Allow-Origin")).contains(FRONT);
        assertThat(header(res, "Access-Control-Allow-Credentials")).contains("true");
        assertThat(header(res, "Access-Control-Allow-Methods").orElseThrow()).contains("PATCH");
    }

    @Test
    @DisplayName("다른 출처 preflight 는 403 이고 허용 헤더가 없다")
    void preflight_from_other_origin_is_rejected() throws Exception {
        HttpResponse<String> res = send("OPTIONS", "/api/v1/me", null,
                "Origin", "https://evil.example", "Access-Control-Request-Method", "PATCH");

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(header(res, "Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    @DisplayName("요청 ID 를 돌려주고 종료 로그에 남긴다. 경로 타입 오류는 400 봉투, 쿼리스트링은 로그에 없다")
    void request_id_and_exit_log(CapturedOutput output) throws Exception {
        HttpResponse<String> res = send("GET", "/api/v1/events/abc?code=secret-oauth-code", null,
                "X-Request-Id", "req-abc_123", "Origin", FRONT);

        assertThat(res.statusCode()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(res.body());
        assertThat(body.path("code").asString()).isEqualTo("INVALID_INPUT");
        assertThat(body.path("message").asString()).startsWith("id: ");
        assertThat(header(res, "X-Request-Id")).contains("req-abc_123");
        assertThat(header(res, "Access-Control-Allow-Origin")).contains(FRONT);
        assertThat(header(res, "Access-Control-Expose-Headers").orElseThrow()).contains("X-Request-Id");

        String exitLine = awaitLine(output, "[API 종료]", "path=/api/v1/events/abc");
        assertThat(exitLine).contains("[req-abc_123]").contains("status=400").contains("handler=EventController.detail")
                .doesNotContain("secret-oauth-code");
    }

    @Test
    @DisplayName("형식이 안전하지 않은 X-Request-Id 는 버리고 새로 만든다")
    void unsafe_request_id_is_replaced() throws Exception {
        HttpResponse<String> res = send("GET", "/api/v1/events/abc", null, "X-Request-Id", "bad id with spaces");

        assertThat(header(res, "X-Request-Id").orElseThrow()).isNotEqualTo("bad id with spaces").matches("[0-9a-f-]{36}");
    }

    @Test
    @DisplayName("동의 전 쓰기는 403 CONSENT_REQUIRED, 탈퇴(DELETE /me)는 동의 전에도 된다")
    void consent_gate_blocks_writes_but_allows_withdrawal() throws Exception {
        jdbc.update("INSERT INTO users (id, nickname, locale, is_admin, created_at, updated_at) "
                + "VALUES (?, 'unconsented', 'ko', FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", USER_ID);
        String token = tokens.issue(USER_ID, false);

        HttpResponse<String> blocked = send("PATCH", "/api/v1/me", "{\"nickname\":\"x\"}",
                "Authorization", "Bearer " + token, "Content-Type", "application/json");
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(blocked.body()).path("code").asString()).isEqualTo("CONSENT_REQUIRED");

        HttpResponse<String> withdrawn = send("DELETE", "/api/v1/me", null, "Authorization", "Bearer " + token);
        assertThat(withdrawn.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, USER_ID)).isTrue();
    }

    @Test
    @DisplayName("Swagger: 자물쇠는 인증이 필요한 operation 에만, @CurrentUser 는 파라미터에서 숨김, LocalizedText 는 로케일 키까지")
    void openapi_security_and_hidden_current_user() throws Exception {
        JsonNode doc = objectMapper.readTree(send("GET", "/v3/api-docs/api-v1", null).body());
        JsonNode paths = doc.path("paths");

        assertThat(doc.has("security")).isFalse();
        assertThat(paths.path("/api/v1/auth/{provider}/authorize").path("get").has("security")).isFalse();
        assertThat(paths.path("/api/v1/auth/token").path("post").has("security")).isFalse();
        assertThat(paths.path("/api/v1/admin/youtube/callback").path("get").has("security")).isFalse();
        assertThat(security(paths, "/api/v1/me", "get")).isEqualTo("[{\"BearerAuth\":[]}]");
        assertThat(security(paths, "/api/v1/me", "patch")).isEqualTo("[{\"BearerAuth\":[]}]");
        assertThat(security(paths, "/api/v1/admin/sync-logs", "get")).isEqualTo("[{\"BearerAuth\":[]}]");
        // 로그인 선택(@CurrentUser(required = false)): 토큰 없이도 호출되고, 있으면 보낸다
        assertThat(security(paths, "/api/v1/events/{id}", "get")).isEqualTo("[{},{\"BearerAuth\":[]}]");
        assertThat(paths.path("/api/v1/me").path("get").path("parameters").toString()).doesNotContain("\"name\":\"user\"");
        assertThat(doc.toString()).contains("\"zh-TW\"");
    }

    @Test
    @DisplayName("Swagger 그룹 api-v2·api-v3·test 도 문서를 낸다")
    void reserved_and_test_groups_are_served() throws Exception {
        for (String group : List.of("api-v2", "api-v3", "test")) {
            assertThat(send("GET", "/v3/api-docs/" + group, null).statusCode()).as(group).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("MVC JSON 변환기가 PATCH 의 Optional 규칙(키 없음 = null) 설정을 쓴다")
    void mvc_json_converter_uses_patch_semantics() {
        assertThat(handlerAdapter.getMessageConverters()).anySatisfy(converter -> {
            assertThat(converter).isInstanceOf(JacksonJsonHttpMessageConverter.class);
            assertThat(((JacksonJsonHttpMessageConverter) converter).getMapper()
                    .isEnabled(DeserializationFeature.USE_NULL_FOR_MISSING_REFERENCE_VALUES)).isTrue();
        });
    }

    private HttpResponse<String> send(String method, String path, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Optional<String> header(HttpResponse<String> res, String name) {
        return res.headers().firstValue(name);
    }

    private static String security(JsonNode paths, String path, String method) {
        return paths.path(path).path(method).path("security").toString();
    }

    // 종료 로그는 응답을 보낸 뒤 afterCompletion 에서 남으므로 잠깐 기다린다
    private static String awaitLine(CapturedOutput output, String... fragments) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Optional<String> line = output.getAll().lines()
                    .filter(l -> List.of(fragments).stream().allMatch(l::contains))
                    .findFirst();
            if (line.isPresent()) {
                return line.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("로그 줄을 찾지 못했다: " + List.of(fragments));
    }
}
