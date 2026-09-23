package com.janspace.event.api;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.event.application.sync.SyncIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * POST /admin/sync/{source}: 비관리자 403, 알 수 없는 출처 400, 동기 실행 후 요약, 제공자 실패 502.
 */
class AdminSyncControllerIntegrationTest extends SyncIntegrationTest {

    static final String PATH = "/api/v1/admin/sync/";

    @Autowired
    WebApplicationContext context;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @WithMockUser
    @DisplayName("ADMIN 권한이 없으면 403 FORBIDDEN")
    void non_admin_is_forbidden() throws Exception {
        mockMvc.perform(post(PATH + "naver"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("알 수 없는 출처는 400 SYNC_SOURCE_UNSUPPORTED")
    void unknown_source_is_bad_request() throws Exception {
        mockMvc.perform(post(PATH + "melon"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYNC_SOURCE_UNSUPPORTED"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("naver 트리거는 동기로 실행하고 {source, status, summary} 를 돌려준다")
    void admin_triggers_naver_sync() throws Exception {
        providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith("https://calendar.naver.com/")))
                .andRespond(fixture("naver/schedules.json", MediaType.APPLICATION_JSON));

        mockMvc.perform(post(PATH + "naver"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.source").value("NAVER"))
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.summary").value(Matchers.startsWith("네이버 캘린더 ")));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("제공자 5xx 는 502 SYNC_PROVIDER_ERROR 로 응답하고 이력은 FAILED 로 남는다")
    void provider_failure_is_bad_gateway() throws Exception {
        providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith("https://calendar.naver.com/")))
                .andRespond(withServerError());

        mockMvc.perform(post(PATH + "naver"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SYNC_PROVIDER_ERROR"));
        org.assertj.core.api.Assertions.assertThat(runs()).singleElement()
                .extracting(r -> r.getStatus().name()).isEqualTo("FAILED");
    }
}
