package com.stagelog.ops.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.PagedResponse;
import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.ops.application.AdminAuditQuery;
import com.stagelog.ops.application.AdminAuditQuery.AuditSearch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 감사 로그 조회 API. /admin/** 은 SecurityConfig 가 ADMIN 권한을 요구한다.
 */
@Slf4j
@Tag(name = "관리자 감사 로그 (Admin Audit)", description = "관리자 조치 감사 로그 조회 API")
@RestController
@RequestMapping(V1_BASE + "/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private final AdminAuditQuery auditQuery;

    @Operation(summary = "감사 로그 조회",
            description = "관리자 조치 감사 로그를 기록 시각 최신순으로 페이지 조회합니다. 조건은 모두 선택이며 from 은 포함, to 는 제외합니다. size 는 1~100 으로 보정되며 기본값은 20 입니다.")
    @GetMapping
    public ResponseDTO<PagedResponse<AuditLogResponse>> list(
            @Parameter(description = "대상 종류 (SOURCE_CHANGE / EVENT / VENUE / UNMATCHED_NAME / PUSH_DELIVERY 등)", example = "EVENT")
            @RequestParam(required = false) String targetType,
            @Parameter(description = "대상 ID", example = "12")
            @RequestParam(required = false) String targetId,
            @Parameter(description = "조치 주체 식별자 (ADMIN 이면 관리자 사용자 ID)", example = "1")
            @RequestParam(required = false) String actorId,
            @Parameter(description = "시작 시각 (UTC, 포함)", example = "2026-11-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "끝 시각 (UTC, 제외)", example = "2026-11-06T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @Parameter(description = "페이지 번호 (0부터)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (1~100, 기본 20)", example = "20")
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        Page<AuditLogResponse> logs = auditQuery.search(new AuditSearch(targetType, targetId, actorId, from, to),
                Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE)).map(AuditLogResponse::from);
        log.info("[감사 로그 조회] targetType={}, targetId={}, actorId={}, page={}, count={}", targetType, targetId, actorId, page,
                logs.getNumberOfElements());
        return ResponseDTO.okWithData(PagedResponse.from(logs));
    }
}
