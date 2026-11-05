package com.stagelog.ops.api;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.ops.domain.SyncRun;
import com.stagelog.ops.domain.SyncRunRepository;
import com.stagelog.ops.domain.SyncSource;
import com.stagelog.ops.domain.SyncStatus;
import com.stagelog.support.IntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 동기화 이력 API: 최신순·size 보정·ADMIN 권한. 관리자 판별은 SecurityConfig 의 /admin/** 규칙이며 ADMIN 권한은 account 의 JWT 필터가 부여한다.
 */
class AdminOpsControllerIntegrationTest extends IntegrationTest {

    static final String PATH = "/api/v1/admin/sync-logs";

    @Autowired
    WebApplicationContext context;

    @Autowired
    SyncRunRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;
    Long oldestId;
    Long newestId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        repository.deleteAll();
        Instant base = Instant.parse("2026-09-24T03:00:00Z");
        oldestId = repository.save(SyncRun.start(SyncSource.NAVER, base)).getId();
        SyncRun middle = SyncRun.start(SyncSource.KOPIS, base.plusSeconds(60));
        middle.finish(SyncStatus.OK, "행사 12건 갱신", base.plusSeconds(70));
        repository.save(middle);
        newestId = repository.save(SyncRun.start(SyncSource.YOUTUBE, base.plusSeconds(120))).getId();
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("ADMIN 은 시작 시각 최신순으로 size 만큼 받는다")
    void admin_gets_newest_first() throws Exception {
        mockMvc.perform(get(PATH).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(newestId))
                .andExpect(jsonPath("$.data[0].source").value("YOUTUBE"))
                .andExpect(jsonPath("$.data[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data[0].ranAt").value("2026-09-24T03:02:00Z"))
                .andExpect(jsonPath("$.data[0].finishedAt").doesNotExist())
                .andExpect(jsonPath("$.data[1].source").value("KOPIS"))
                .andExpect(jsonPath("$.data[1].status").value("OK"))
                .andExpect(jsonPath("$.data[1].finishedAt").value("2026-09-24T03:01:10Z"))
                .andExpect(jsonPath("$.data[1].message").value("행사 12건 갱신"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("size 생략은 기본 100, 500 초과는 500 으로 보정된다")
    void size_defaults_and_clamps() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[2].id").value(oldestId));
        mockMvc.perform(get(PATH).param("size", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    @DisplayName("인증 없으면 401 봉투 (공개 GET 규칙보다 /admin/** 이 우선)")
    void anonymous_is_401() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("ADMIN 권한 없는 로그인 사용자는 403 봉투")
    void non_admin_is_403() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("실행마다 실패 항목 수(sync_item_failures)를 낸다. 실패가 없으면 0")
    void failed_item_count_per_run() throws Exception {
        jdbcTemplate.update("DELETE FROM sync_item_failures");
        for (String externalId : new String[] {"PF1", "PF2"}) {
            jdbcTemplate.update("INSERT INTO sync_item_failures (sync_run_id, provider, external_id, error_code, message, created_at, updated_at) "
                    + "VALUES (?, 'KOPIS', ?, 'ResourceAccessException', 'Read timed out', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", oldestId, externalId);
        }
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].failedItemCount").value(0))
                .andExpect(jsonPath("$.data[2].id").value(oldestId))
                .andExpect(jsonPath("$.data[2].failedItemCount").value(2));
    }
}
