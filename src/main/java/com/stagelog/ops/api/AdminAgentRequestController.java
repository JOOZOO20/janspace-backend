package com.stagelog.ops.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.auth.CurrentUser;
import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.ops.api.AgentRequestDtos.AgentRequestResponse;
import com.stagelog.ops.api.AgentRequestDtos.CreateAgentRequest;
import com.stagelog.ops.api.AgentRequestDtos.CreatedResponse;
import com.stagelog.ops.application.AgentRequestUseCase;
import com.stagelog.ops.application.AgentRequestUseCase.NewRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 조치 요청. 생성은 MCP_REQUESTER 서비스 계정만, 목록·승인·거절은 ADMIN 만 한다 (SecurityConfig).
 */
@Slf4j
@Tag(name = "관리자 MCP 조치 요청 (Admin Agent Requests)", description = "AI 진단이 만든 조치 요청의 생성·승인 대기 목록·승인·거절")
@RestController
@RequestMapping(V1_BASE + "/admin/agent-requests")
@RequiredArgsConstructor
public class AdminAgentRequestController {

    public static final String CLIENT_HEADER = "X-Mcp-Client";
    // 서비스 계정 이름(최대 50) + ":" + 클라이언트 ID 가 requested_by(100)에 들어간다
    static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9_.-]{1,49}");

    private final AgentRequestUseCase useCase;

    @Operation(summary = "조치 요청 생성 (MCP_REQUESTER)",
            description = "조치를 실행하지 않고 승인 대기(PENDING) 요청만 만듭니다. 같은 유형·대상의 PENDING 이 있으면 새로 만들지 않고 그 ID 를 "
                    + "duplicate=true 로 돌려줍니다. 유형·대상·근거 형식이 틀리면 400 INVALID_INPUT.")
    @PostMapping
    public ResponseDTO<CreatedResponse> create(
            @RequestBody @Valid CreateAgentRequest request,
            @Parameter(description = "요청을 만든 MCP 클라이언트 ID (MCP 서버가 붙임)", example = "eval-claude")
            @RequestHeader(value = CLIENT_HEADER, required = false) String client,
            @Parameter(hidden = true) Principal serviceAccount) {
        String requestedBy = client != null && CLIENT_ID.matcher(client).matches()
                ? serviceAccount.getName() + ":" + client : serviceAccount.getName();
        return ResponseDTO.okWithData(CreatedResponse.from(useCase.create(new NewRequest(request.type(), request.targetRef(),
                request.payload(), request.reason(), request.evidenceRefs(), requestedBy))));
    }

    @Operation(summary = "조치 요청 목록", description = "상태별 조치 요청을 최신순으로 최대 100건 돌려줍니다. 기본은 승인 대기(PENDING).")
    @GetMapping
    public ResponseDTO<List<AgentRequestResponse>> list(
            @Parameter(description = "상태 (PENDING / APPROVED / REJECTED / EXECUTED / FAILED)", example = "PENDING")
            @RequestParam(defaultValue = "PENDING") String status) {
        return ResponseDTO.okWithData(useCase.list(status).stream().map(AgentRequestResponse::from).toList());
    }

    @Operation(summary = "조치 요청 승인",
            description = "PENDING 요청을 승인하고 기존 실행 경로로 처리합니다. 성공하면 EXECUTED, 실행이 실패하면 FAILED 로 남고 실행 오류가 그대로 응답됩니다. "
                    + "PENDING 이 아니면 409 AGENT_REQUEST_NOT_PENDING, 실행 경로가 아직 없는 유형이면 409 AGENT_ACTION_NOT_EXECUTABLE(상태는 PENDING 유지).")
    @PostMapping("/{id}/approve")
    public ResponseDTO<AgentRequestResponse> approve(
            @Parameter(description = "조치 요청 ID", example = "7") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser admin) {
        log.info("[관리자 MCP 조치 승인 요청] requestId={}, adminId={}", id, admin.userId());
        return ResponseDTO.okWithData(AgentRequestResponse.from(useCase.approve(id, admin.userId())));
    }

    @Operation(summary = "조치 요청 거절", description = "PENDING 요청을 REJECTED 로 닫습니다. PENDING 이 아니면 409 AGENT_REQUEST_NOT_PENDING.")
    @PostMapping("/{id}/reject")
    public ResponseDTO<AgentRequestResponse> reject(
            @Parameter(description = "조치 요청 ID", example = "7") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser admin) {
        log.info("[관리자 MCP 조치 거절 요청] requestId={}, adminId={}", id, admin.userId());
        return ResponseDTO.okWithData(AgentRequestResponse.from(useCase.reject(id, admin.userId())));
    }
}
