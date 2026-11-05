package com.stagelog.event.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.event.api.EventDiagnosisResponses.ComparisonResponse;
import com.stagelog.event.api.EventDiagnosisResponses.SyncStatusResponse;
import com.stagelog.event.application.EventDiagnosisQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 행사 진단 조회. ADMIN 과 MCP_READER 서비스 계정이 부른다 (SecurityConfig). 읽기 전용이다.
 */
@Slf4j
@Tag(name = "관리자 행사 진단 (Admin Event Diagnosis)", description = "행사 동기화 상태와 필드별 출처 비교 (MCP 진단 Tool 근거)")
@RestController
@RequestMapping(V1_BASE + "/admin/events")
@RequiredArgsConstructor
public class AdminEventDiagnosisController {

    private final EventDiagnosisQuery diagnosisQuery;

    @Operation(summary = "행사 동기화 상태",
            description = "연결된 출처별 최근 Snapshot 시각, 그 출처의 마지막 동기화 실행, 확인 대기(PENDING) 출처 변경, Field lock 목록을 돌려줍니다. 없는 행사는 404 EVENT_NOT_FOUND.")
    @GetMapping("/{id}/sync-status")
    public ResponseDTO<SyncStatusResponse> syncStatus(@Parameter(description = "행사 ID", example = "12") @PathVariable Long id) {
        SyncStatusResponse response = SyncStatusResponse.from(diagnosisQuery.syncStatus(id));
        log.info("[행사 동기화 상태 조회] eventId={}, providers={}, pendingChanges={}", id, response.providers().size(),
                response.pendingChangeCount());
        return ResponseDTO.okWithData(response);
    }

    @Operation(summary = "필드별 출처 비교",
            description = "한 필드의 출처별 값, 우선순위, 우선순위로 고른 값과 현재 값이 그렇게 된 이유(잠금·거절 이력·확인 대기·다음 접기 대기)를 돌려줍니다. "
                    + "접기 대상이 아닌 필드는 400 INVALID_INPUT, 없는 행사는 404 EVENT_NOT_FOUND.")
    @GetMapping("/{id}/source-comparison")
    public ResponseDTO<ComparisonResponse> compare(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @Parameter(description = "필드 (NAME, VENUE, EVENT_DATE, STARTS_AT, POSTER, DESCRIPTION, TICKET_URL, RUNNING_MINUTES)", example = "NAME")
            @RequestParam String field) {
        ComparisonResponse response = ComparisonResponse.from(diagnosisQuery.compare(id, field));
        log.info("[행사 출처 비교 조회] eventId={}, field={}, reason={}", id, response.field(), response.reason());
        return ResponseDTO.okWithData(response);
    }
}
