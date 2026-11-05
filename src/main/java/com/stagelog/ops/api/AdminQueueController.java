package com.stagelog.ops.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.auth.CurrentUser;
import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.ops.application.DeadLetters;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 Queue(DLQ) API. /admin/** 은 SecurityConfig 가 ADMIN 권한을 요구한다.
 */
@Slf4j
@Tag(name = "관리자 Queue (Admin Queue)", description = "수집·후처리 Queue 의 DLQ 조회와 redrive API")
@RestController
@RequestMapping(V1_BASE + "/admin/queues")
@RequiredArgsConstructor
public class AdminQueueController {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 100;

    private final DeadLetters deadLetters;

    @Operation(summary = "DLQ 메시지 조회",
            description = "Queue 의 DLQ 메시지를 메시지 ID, 처리 실패 횟수, 마지막 오류, 본문 앞부분으로 조회합니다. limit 는 1~100 으로 보정되며 기본 50 입니다. "
                    + "SQS 에는 지우지 않고 보는 기능이 없어 짧게 받아 본 뒤 그대로 두므로, 메시지가 많으면 일부만 보일 수 있습니다. 없는 Queue 는 404 QUEUE_NOT_FOUND 입니다.")
    @GetMapping("/{queue}/dead-letters")
    public ResponseDTO<List<DeadLetterResponse>> deadLetters(
            @Parameter(description = "Queue 이름 (jan-source-apply | jan-poster-ingest)", example = "jan-poster-ingest")
            @PathVariable String queue,
            @Parameter(description = "조회 건수 (1~100, 기본 50)", example = "50")
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        List<DeadLetterResponse> letters = deadLetters.list(queue, Math.clamp(limit, 1, MAX_LIMIT)).stream()
                .map(DeadLetterResponse::from).toList();
        return ResponseDTO.okWithData(letters);
    }

    @Operation(summary = "DLQ 메시지 redrive",
            description = "지정한 메시지를 DLQ 에서 원래 Queue 로 다시 넣습니다. 찾지 못한 ID 는 notFound 로 돌려줍니다. "
                    + "Consumer 는 중복 수신을 전제로 하므로 같은 메시지를 두 번 넣어도 결과는 같습니다.")
    @PostMapping("/{queue}/dead-letters/redrive")
    public ResponseDTO<RedriveResponse> redrive(
            @Parameter(description = "Queue 이름 (jan-source-apply | jan-poster-ingest)", example = "jan-poster-ingest")
            @PathVariable String queue,
            @RequestBody @Valid RedriveRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser admin) {
        log.info("[DLQ redrive 요청] queue={}, count={}", queue, request.messageIds().size());
        return ResponseDTO.okWithData(RedriveResponse.from(deadLetters.redrive(queue, request.messageIds(), AuditActor.admin(admin.userId()))));
    }
}
