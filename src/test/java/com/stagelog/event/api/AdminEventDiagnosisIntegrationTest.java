package com.stagelog.event.api;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.SourceChangeReviewUseCase;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventSourceChangeRepository;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.SourceChangeStatus;
import com.stagelog.event.support.EventIntegrationTest;
import com.stagelog.ops.application.SyncRunRecorder;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 진단 조회: 출처별 Snapshot·마지막 실행·PENDING·Field lock, 필드별 출처 비교의 우선순위와 이유(PRIORITY·LOCKED·PENDING_REVIEW·REJECTED).
 */
@WithMockUser(authorities = "ADMIN")
class AdminEventDiagnosisIntegrationTest extends EventIntegrationTest {

    static final LocalDateTime AT_19 = DATE.atTime(19, 0);
    static final LocalDateTime AT_20 = DATE.atTime(20, 0);

    @Autowired
    WebApplicationContext context;
    @Autowired
    ApplySourcesUseCase applySources;
    @Autowired
    SourceChangeReviewUseCase review;
    @Autowired
    EventSourceRepository sourceRepository;
    @Autowired
    EventSourceChangeRepository changeRepository;
    @Autowired
    SyncRunRecorder syncRuns;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("동기화 상태: 출처별 snapshotId·수집 시각, 그 출처의 마지막 실행(runId·상태), PENDING 변경, Field lock")
    void sync_status_lists_evidence_ids() throws Exception {
        Event event = newEventWithStart("스타더스트 클럽 단독 공연", DATE, AT_19);
        Long id = event.getId();
        applySources.apply(id, Map.of(
                Provider.KOPIS, snapshot("PF1", DATE.toString(), "KOPIS 이름", AT_20, DATE, null),
                Provider.NAVER, snapshot("N1", DATE.toString(), "네이버 이름", null, DATE, null)));
        Long runId = syncRuns.start("KOPIS");
        syncRuns.fail(runId, "KOPIS 응답 오류");
        Long kopisSnapshot = sourceRepository.findByEventIdAndProvider(id, Provider.KOPIS).orElseThrow().getId();
        Long changeId = changeRepository.findAllByEventIdAndStatus(id, SourceChangeStatus.PENDING).get(0).getId();

        // 출처는 Provider 순서(NAVER, KOPIS, ...)
        mvc.perform(get("/api/v1/admin/events/{id}/sync-status", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value(id))
                .andExpect(jsonPath("$.data.providers.length()").value(2))
                .andExpect(jsonPath("$.data.providers[0].provider").value("NAVER"))
                .andExpect(jsonPath("$.data.providers[1].provider").value("KOPIS"))
                .andExpect(jsonPath("$.data.providers[1].snapshotId").value(kopisSnapshot))
                .andExpect(jsonPath("$.data.providers[1].externalId").value("PF1"))
                .andExpect(jsonPath("$.data.providers[1].lastRun.runId").value(runId))
                .andExpect(jsonPath("$.data.providers[1].lastRun.status").value("FAILED"))
                .andExpect(jsonPath("$.data.providers[1].lastRun.message").value("KOPIS 응답 오류"))
                .andExpect(jsonPath("$.data.pendingChangeCount").value(1))
                .andExpect(jsonPath("$.data.pendingChanges[0].changeId").value(changeId))
                .andExpect(jsonPath("$.data.pendingChanges[0].field").value("STARTS_AT"))
                .andExpect(jsonPath("$.data.lockedFields.length()").value(0));
    }

    @Test
    @DisplayName("출처 비교: 이름은 우선순위(NAVER > KOPIS) 값이 채택되면 PRIORITY, 잠그면 LOCKED")
    void compare_name_priority_then_locked() throws Exception {
        Long id = newEvent("처음 이름", DATE).getId();
        applySources.apply(id, Map.of(
                Provider.KOPIS, snapshot("PF1", DATE.toString(), "KOPIS 이름", null, DATE, null),
                Provider.NAVER, snapshot("N1", DATE.toString(), "네이버 이름", null, DATE, null)));

        mvc.perform(get("/api/v1/admin/events/{id}/source-comparison", id).param("field", "name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priority[0]").value("NAVER"))
                .andExpect(jsonPath("$.data.currentValue").value("네이버 이름"))
                .andExpect(jsonPath("$.data.pickedProvider").value("NAVER"))
                .andExpect(jsonPath("$.data.reason").value("PRIORITY"))
                .andExpect(jsonPath("$.data.values[0].provider").value("NAVER"))
                .andExpect(jsonPath("$.data.values[0].rank").value(1))
                .andExpect(jsonPath("$.data.values[1].value").value("KOPIS 이름"));

        Event event = eventRepository.findById(id).orElseThrow();
        event.adminUpdate(EventLockableField.NAME, "관리자 이름");
        eventRepository.save(event);

        mvc.perform(get("/api/v1/admin/events/{id}/source-comparison", id).param("field", "NAME"))
                .andExpect(jsonPath("$.data.locked").value(true))
                .andExpect(jsonPath("$.data.currentValue").value("관리자 이름"))
                .andExpect(jsonPath("$.data.reason").value("LOCKED"));
    }

    @Test
    @DisplayName("출처 비교: 민감 필드의 값 -> 다른 값은 PENDING_REVIEW(변경 ID), 거절하면 REJECTED(거절 행 ID)")
    void compare_starts_at_pending_then_rejected() throws Exception {
        Long id = newEventWithStart("스타더스트 클럽 단독 공연", DATE, AT_19).getId();
        applySources.apply(id, Map.of(Provider.KOPIS, snapshot("PF1", DATE.toString(), "공연", AT_20, DATE, null)));
        Long changeId = changeRepository.findAllByEventIdAndStatus(id, SourceChangeStatus.PENDING).get(0).getId();

        mvc.perform(get("/api/v1/admin/events/{id}/source-comparison", id).param("field", "STARTS_AT"))
                .andExpect(jsonPath("$.data.currentValue").value("2026-10-23T19:00"))
                .andExpect(jsonPath("$.data.pickedValue").value("2026-10-23T20:00"))
                .andExpect(jsonPath("$.data.reason").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.data.sourceChangeId").value(changeId));

        review.reject(changeId);

        mvc.perform(get("/api/v1/admin/events/{id}/source-comparison", id).param("field", "STARTS_AT"))
                .andExpect(jsonPath("$.data.reason").value("REJECTED"))
                .andExpect(jsonPath("$.data.sourceChangeId").value(changeId));
    }

    @Test
    @DisplayName("접기 대상이 아닌 필드는 400, 없는 행사는 404")
    void invalid_field_and_missing_event() throws Exception {
        Long id = newEvent("공연", DATE).getId();
        mvc.perform(get("/api/v1/admin/events/{id}/source-comparison", id).param("field", "SEASON"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/events/{id}/sync-status", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }
}
