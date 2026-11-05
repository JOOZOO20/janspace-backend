package com.stagelog.ops.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.ops.application.SyncRunQuery;
import com.stagelog.ops.domain.SyncRun;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 운영 API. /admin/** 은 SecurityConfig 가 ADMIN 권한을 요구한다.
 */
@Slf4j
@Tag(name = "관리자 운영 (Admin Ops)", description = "외부 출처 동기화 실행 이력 조회 API")
@RestController
@RequestMapping(V1_BASE + "/admin/sync-logs")
@RequiredArgsConstructor
public class AdminOpsController {

    static final int DEFAULT_SIZE = 100;
    static final int MAX_SIZE = 500;

    private final SyncRunQuery syncRunQuery;

    @Operation(summary = "동기화 이력 조회",
            description = "외부 출처 동기화 실행 이력을 시작 시각 최신순으로 조회합니다. 실행마다 실패 항목 수(sync_item_failures)를 함께 냅니다. "
                    + "size 는 1~500 으로 보정되며 기본값은 100 입니다.")
    @GetMapping
    public ResponseDTO<List<SyncLogResponse>> list(
            @Parameter(description = "조회 건수 (1~500, 기본 100)", example = "100")
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        int limit = Math.clamp(size, 1, MAX_SIZE);
        List<SyncRun> runs = syncRunQuery.latest(limit);
        Map<Long, Long> failures = syncRunQuery.failureCounts(runs.stream().map(SyncRun::getId).toList());
        List<SyncLogResponse> logs = runs.stream().map(run -> SyncLogResponse.from(run, failures.getOrDefault(run.getId(), 0L))).toList();
        log.info("[동기화 이력 조회] size={}, count={}", limit, logs.size());
        return ResponseDTO.okWithData(logs);
    }
}
