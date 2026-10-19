package com.stagelog.notification.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.notification.application.AdminPushCommand;
import com.stagelog.notification.application.DispatchDuePushJobs;
import com.stagelog.notification.application.PushJobQuery;
import com.stagelog.notification.domain.PushJob;
import com.stagelog.notification.domain.PushJobStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 푸시 운영 API. /admin/** 은 SecurityConfig 가 ADMIN 권한을 요구한다.
 */
@Slf4j
@Tag(name = "관리자 푸시 (Admin Push)", description = "푸시 잡·배달 상태 조회, 수동 재시도, 즉시 디스패치 API")
@RestController
@RequestMapping(V1_BASE + "/admin")
@RequiredArgsConstructor
public class AdminPushController {

    static final int DEFAULT_SIZE = 100;
    static final int MAX_SIZE = 500;

    private final PushJobQuery pushJobQuery;
    private final AdminPushCommand adminPushCommand;
    private final DispatchDuePushJobs dispatchDuePushJobs;

    @Operation(summary = "푸시 잡 목록", description = "최신 잡부터 size 건. status 로 거를 수 있습니다. size 는 1~500, 기본 100.")
    @GetMapping("/push-jobs")
    public ResponseDTO<List<PushJobResponse>> jobs(
            @Parameter(description = "잡 상태 필터 (SCHEDULED/DISPATCHING/DONE/CANCELLED). 없으면 전체", example = "DISPATCHING")
            @RequestParam(required = false) PushJobStatus status,
            @Parameter(description = "조회 건수 (1~500, 기본 100)", example = "100")
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        List<PushJobResponse> out = pushJobQuery.list(status, Math.clamp(size, 1, MAX_SIZE)).stream()
                .map(j -> PushJobResponse.from(j, null)).toList();
        return ResponseDTO.okWithData(out);
    }

    @Operation(summary = "푸시 잡 상세", description = "잡과 배달 전부(상태·시도 횟수·실패 사유). 없으면 404 PUSH_JOB_NOT_FOUND.")
    @GetMapping("/push-jobs/{id}")
    public ResponseDTO<PushJobResponse> job(@Parameter(description = "잡 ID", example = "42") @PathVariable Long id) {
        PushJob job = pushJobQuery.job(id);
        return ResponseDTO.okWithData(PushJobResponse.from(job, pushJobQuery.deliveries(id)));
    }

    @Operation(summary = "배달 수동 재시도", description = "FAILED 배달을 RETRY 로 되돌립니다 (attempts 유지). FAILED 가 아니면 409 PUSH_DELIVERY_NOT_RETRYABLE.")
    @PostMapping("/push-deliveries/{id}/retry")
    public ResponseDTO<PushDeliveryResponse> retry(@Parameter(description = "배달 ID", example = "501") @PathVariable Long id) {
        return ResponseDTO.okWithData(PushDeliveryResponse.from(adminPushCommand.retryDelivery(id)));
    }

    @Operation(summary = "디스패치 즉시 실행", description = "1분 주기를 기다리지 않고 디스패처 한 사이클(선점·물질화·전송·회수·완료)을 지금 돌립니다.")
    @PostMapping("/push/dispatch")
    public ResponseDTO<DispatchResultResponse> dispatch() {
        log.info("[푸시 디스패치 수동 실행]");
        return ResponseDTO.okWithData(DispatchResultResponse.from(dispatchDuePushJobs.runCycle()));
    }
}
