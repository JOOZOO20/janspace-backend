package com.stagelog.ops.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.account.application.ServiceAccountAuthenticator;
import com.stagelog.account.domain.ServiceAccount;
import com.stagelog.account.domain.ServiceAccountRepository;
import com.stagelog.account.domain.ServiceAccountRole;
import com.stagelog.bootstrap.ServiceAccountAuthenticationFilter;
import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.ops.domain.AgentActionRequestRepository;
import com.stagelog.setlist.application.SyncPlaylistUseCase;
import com.stagelog.setlist.application.UnmatchedUseCase;
import com.stagelog.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 조치 요청: 생성은 실행 없이 PENDING 만 만들고, 같은 대상 PENDING 중복(동시 요청 포함)은 기존 ID 를 돌려준다.
 * 승인은 기존 실행 경로(재생목록 동기화·미매칭 연결)를 부르고 EXECUTED/FAILED 를 남긴다. 실행 경로 자체는 목으로 둔다.
 */
class AdminAgentRequestIntegrationTest extends IntegrationTest {

    static final String PATH = "/api/v1/admin/agent-requests";
    static final String KEY = "test-agent-requester-key-0123456789";
    static final long ADMIN_ID = 990_101L;

    @MockitoBean
    SyncPlaylistUseCase syncPlaylist;
    @MockitoBean
    UnmatchedUseCase unmatchedUseCase;

    @Autowired
    WebApplicationContext context;
    @Autowired
    ServiceAccountRepository accounts;
    @Autowired
    AgentActionRequestRepository requests;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        requests.deleteAll();
        accounts.deleteAll();
        accounts.save(new ServiceAccount("jan-mcp", ServiceAccountAuthenticator.sha256Hex(KEY),
                Set.of(ServiceAccountRole.MCP_READER, ServiceAccountRole.MCP_REQUESTER)));
        // 관리자 승인·거절은 쓰기라 동의 게이트가 users 행을 본다
        jdbc.update("DELETE FROM users WHERE id = ?", ADMIN_ID);
        jdbc.update("INSERT INTO users (id, nickname, locale, is_admin, consented_at, terms_version, created_at, updated_at) "
                + "VALUES (?, 'admin', 'ko', TRUE, UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", ADMIN_ID);
    }

    @Test
    @DisplayName("생성은 PENDING 만 만들고 실행하지 않는다. 같은 대상 PENDING 이면 기존 ID 를 duplicate=true 로 돌려준다")
    void duplicate_pending_returns_existing_id() throws Exception {
        JsonNode first = create("RETRY_PLAYLIST_SYNC", "12", null);
        JsonNode second = create("RETRY_PLAYLIST_SYNC", "12", null);
        JsonNode otherTarget = create("RETRY_PLAYLIST_SYNC", "13", null);

        assertThat(first.path("duplicate").asBoolean()).isFalse();
        assertThat(second.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(second.path("duplicate").asBoolean()).isTrue();
        assertThat(otherTarget.path("id").asLong()).isNotEqualTo(first.path("id").asLong());
        assertThat(requests.count()).isEqualTo(2);
        verify(syncPlaylist, never()).syncOrThrow(anyLong());

        mvc.perform(get(PATH).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].id").value(first.path("id").asLong()))
                .andExpect(jsonPath("$.data[1].status").value("PENDING"))
                .andExpect(jsonPath("$.data[1].requestedBy").value("jan-mcp:eval-claude"))
                .andExpect(jsonPath("$.data[1].evidenceRefs[0]").value("run:42"));
    }

    @Test
    @DisplayName("같은 대상을 동시에 8번 요청해도 PENDING 은 1건이고 모두 같은 ID 를 받는다 (DB UNIQUE)")
    void concurrent_duplicates_collapse_to_one_row() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> create("RETRY_PLAYLIST_SYNC", "77", null).path("id").asLong());
            }
            Set<Long> ids = new java.util.HashSet<>();
            for (Future<Long> f : pool.invokeAll(calls)) {
                ids.add(f.get());
            }
            assertThat(ids).hasSize(1);
            assertThat(requests.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("거절하면 같은 대상으로 다시 요청할 수 있고, 거절된 요청은 다시 승인할 수 없다(409)")
    void rejected_request_frees_target() throws Exception {
        long id = create("RETRY_PLAYLIST_SYNC", "12", null).path("id").asLong();

        mvc.perform(post(PATH + "/" + id + "/reject").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.decidedBy").value(String.valueOf(ADMIN_ID)));
        mvc.perform(post(PATH + "/" + id + "/approve").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_REQUEST_NOT_PENDING"));

        JsonNode again = create("RETRY_PLAYLIST_SYNC", "12", null);
        assertThat(again.path("duplicate").asBoolean()).isFalse();
        assertThat(again.path("id").asLong()).isNotEqualTo(id);
        verify(syncPlaylist, never()).syncOrThrow(anyLong());
    }

    @Test
    @DisplayName("승인하면 기존 실행 경로를 부르고 EXECUTED. RETRY_PLAYLIST_SYNC 는 syncOrThrow, LINK_UNMATCHED_SONG 은 link")
    void approve_runs_existing_paths() throws Exception {
        long playlist = create("RETRY_PLAYLIST_SYNC", "12", null).path("id").asLong();
        long link = create("LINK_UNMATCHED_SONG", "5", "{\"songId\":3}").path("id").asLong();

        mvc.perform(post(PATH + "/" + playlist + "/approve").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.decidedBy").value(String.valueOf(ADMIN_ID)));
        mvc.perform(post(PATH + "/" + link + "/approve").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"))
                .andExpect(jsonPath("$.data.payload.songId").value(3));

        verify(syncPlaylist).syncOrThrow(12L);
        verify(unmatchedUseCase).link(5L, 3L);
    }

    @Test
    @DisplayName("실행이 실패하면 FAILED 로 남고 실행 오류가 관리자에게 그대로 간다")
    void failed_execution_is_recorded() throws Exception {
        when(syncPlaylist.syncOrThrow(12L)).thenThrow(new ConflictException(ErrorCode.PLAYLIST_SYNC_IN_PROGRESS));
        long id = create("RETRY_PLAYLIST_SYNC", "12", null).path("id").asLong();

        mvc.perform(post(PATH + "/" + id + "/approve").with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAYLIST_SYNC_IN_PROGRESS"));

        assertThat(requests.findById(id).orElseThrow().getStatus().name()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("실행 경로가 아직 없는 유형(REDRIVE_DEAD_LETTER·RETRY_MEDIA_RENDITION)은 승인 시 409 이고 PENDING 으로 남는다")
    void unsupported_type_stays_pending() throws Exception {
        for (String type : List.of("REDRIVE_DEAD_LETTER", "RETRY_MEDIA_RENDITION")) {
            long id = create(type, "9", null).path("id").asLong();
            mvc.perform(post(PATH + "/" + id + "/approve").with(admin()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("AGENT_ACTION_NOT_EXECUTABLE"));
            assertThat(requests.findById(id).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        }
    }

    @Test
    @DisplayName("4개 밖 유형, 숫자가 아닌 대상, 근거 없는 요청, songId 없는 LINK_UNMATCHED_SONG 은 400")
    void invalid_requests_are_rejected() throws Exception {
        for (String body : List.of(
                body("DELETE_EVENT", "12", null, "[\"run:1\"]"),
                body("RETRY_PLAYLIST_SYNC", "all events", null, "[\"run:1\"]"),
                body("RETRY_PLAYLIST_SYNC", "12", null, "[]"),
                body("LINK_UNMATCHED_SONG", "5", null, "[\"run:1\"]"))) {
            mvc.perform(post(PATH).header(ServiceAccountAuthenticationFilter.HEADER, KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(requests.count()).isZero();
    }

    private JsonNode create(String type, String targetRef, String payload) throws Exception {
        String response = mvc.perform(post(PATH).header(ServiceAccountAuthenticationFilter.HEADER, KEY)
                        .header(AdminAgentRequestController.CLIENT_HEADER, "eval-claude")
                        .contentType(MediaType.APPLICATION_JSON).content(body(type, targetRef, payload, "[\"run:42\",\"snapshot:7\"]")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private static String body(String type, String targetRef, String payload, String evidence) {
        return "{\"type\":\"" + type + "\",\"targetRef\":\"" + targetRef + "\",\"reason\":\"진단 결과 재시도 필요\","
                + "\"evidenceRefs\":" + evidence + (payload == null ? "" : ",\"payload\":" + payload) + "}";
    }

    private static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ADMIN_ID, true), null,
                List.of(new SimpleGrantedAuthority("ADMIN"))));
    }
}
