package com.stagelog.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.account.application.ServiceAccountAuthenticator;
import com.stagelog.account.domain.ServiceAccount;
import com.stagelog.account.domain.ServiceAccountRepository;
import com.stagelog.account.domain.ServiceAccountRole;
import com.stagelog.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 서비스 계정(X-Api-Key) 인가: MCP 역할은 허용 경로 밖 모든 API 에서 403 이다 (승인 없이 데이터가 바뀌는 경로 없음).
 * 앱에 등록된 모든 핸들러를 전수로 부르므로 새 API 가 생겨도 MCP 역할로 열리지 않았음을 같은 테스트가 확인한다.
 */
class ServiceAccountSecurityIntegrationTest extends IntegrationTest {

    static final String FULL_KEY = "test-mcp-full-key-0123456789abcdef";
    static final String READER_KEY = "test-mcp-reader-key-0123456789abcdef";
    static final String DISABLED_KEY = "test-mcp-disabled-key-0123456789abcdef";
    static final String HEADER = ServiceAccountAuthenticationFilter.HEADER;

    // MCP 역할에 열린 (메서드, 경로 패턴). SecurityConfig 와 같아야 한다
    static final Set<String> ALLOWED = Set.of(
            "GET /api/v1/admin/sync-logs",
            "GET /api/v1/admin/songs/unmatched",
            "GET /api/v1/admin/songs/search",
            "GET /api/v1/admin/events/{id}/sync-status",
            "GET /api/v1/admin/events/{id}/source-comparison",
            "GET /api/v1/admin/events/{id}/setlist/playlist-status",
            "GET /api/v1/admin/youtube/quota",
            "GET /api/v1/admin/queues/{queue}/dead-letters",
            "POST /api/v1/admin/agent-requests");

    @Autowired
    WebApplicationContext context;
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;
    @Autowired
    ServiceAccountRepository accounts;
    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        accounts.deleteAll();
        accounts.save(new ServiceAccount("jan-mcp", ServiceAccountAuthenticator.sha256Hex(FULL_KEY),
                Set.of(ServiceAccountRole.MCP_READER, ServiceAccountRole.MCP_REQUESTER)));
        accounts.save(new ServiceAccount("jan-mcp-reader", ServiceAccountAuthenticator.sha256Hex(READER_KEY),
                Set.of(ServiceAccountRole.MCP_READER)));
        accounts.save(new ServiceAccount("jan-mcp-old", ServiceAccountAuthenticator.sha256Hex(DISABLED_KEY),
                Set.of(ServiceAccountRole.MCP_READER)));
        jdbc.update("UPDATE service_accounts SET disabled_at = UTC_TIMESTAMP(6) WHERE name = 'jan-mcp-old'");
    }

    @Test
    @DisplayName("MCP 역할(READER+REQUESTER)로 허용 경로 밖 모든 API 를 부르면 403 이다 (쓰기 전부와 다른 관리자·사용자 조회 포함)")
    void every_endpoint_outside_allow_list_is_forbidden() throws Exception {
        List<String> checked = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET, RequestMethod.POST) : methods) {
                    String key = method + " " + pattern;
                    if (ALLOWED.contains(key)) {
                        continue;
                    }
                    int status = mvc.perform(request(HttpMethod.valueOf(method.name()), pattern.replaceAll("\\{[^}]+}", "1"))
                                    .header(HEADER, FULL_KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                            .andReturn().getResponse().getStatus();
                    assertThat(status).as(key).isEqualTo(403);
                    checked.add(key);
                }
            }
        }
        // 전수 검사가 실제로 돌았는지: 관리자 쓰기·사용자 쓰기·관리자 승인이 모두 포함된다
        assertThat(checked).contains("POST /api/v1/admin/songs/unmatched/{id}", "POST /api/v1/admin/sync/{source}",
                "POST /api/v1/admin/agent-requests/{id}/approve", "POST /api/v1/admin/source-changes/{id}/approve",
                "GET /api/v1/admin/agent-requests");
        assertThat(checked.size()).isGreaterThan(50);
    }

    @Test
    @DisplayName("MCP_READER 는 Tool 근거 조회 API 를 통과하고(401·403 아님), 조치 요청 생성은 MCP_REQUESTER 가 없어 403")
    void reader_reads_allowed_apis_but_cannot_create_requests() throws Exception {
        for (String uri : List.of("/api/v1/admin/sync-logs", "/api/v1/admin/songs/unmatched", "/api/v1/admin/songs/search?q=a",
                "/api/v1/admin/events/1/sync-status", "/api/v1/admin/events/1/source-comparison?field=NAME",
                "/api/v1/admin/events/1/setlist/playlist-status", "/api/v1/admin/youtube/quota",
                "/api/v1/admin/queues/unknown/dead-letters")) {
            int status = mvc.perform(get(uri).header(HEADER, READER_KEY)).andReturn().getResponse().getStatus();
            assertThat(status).as(uri).isNotIn(401, 403);
        }
        mvc.perform(post("/api/v1/admin/agent-requests").header(HEADER, READER_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("틀린 key·회수된 계정은 허용 경로에서도 401")
    void unknown_or_disabled_key_is_unauthorized() throws Exception {
        mvc.perform(get("/api/v1/admin/sync-logs").header(HEADER, "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/admin/sync-logs").header(HEADER, DISABLED_KEY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("관리자(JWT)는 조치 요청을 만들 수 없다 (403). 요청은 MCP 서비스 계정만 만든다")
    void admin_cannot_create_agent_request() throws Exception {
        mvc.perform(post("/api/v1/admin/agent-requests").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
