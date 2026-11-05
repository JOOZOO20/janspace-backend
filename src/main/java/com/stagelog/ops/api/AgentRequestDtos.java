package com.stagelog.ops.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.ops.application.AgentRequestUseCase.AgentRequestView;
import com.stagelog.ops.application.AgentRequestUseCase.Created;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * MCP 조치 요청 API 의 요청·응답 DTO.
 */
public final class AgentRequestDtos {

    private AgentRequestDtos() {
    }

    @Schema(description = "MCP 조치 요청 생성. 조치를 실행하지 않고 관리자 승인 대기 요청만 만든다")
    public record CreateAgentRequest(
            @Schema(description = "조치 유형", example = "RETRY_PLAYLIST_SYNC", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"RETRY_PLAYLIST_SYNC", "REDRIVE_DEAD_LETTER", "LINK_UNMATCHED_SONG", "RETRY_MEDIA_RENDITION"})
            @NotBlank String type,
            @Schema(description = "조치 대상의 숫자 ID (RETRY_PLAYLIST_SYNC = 행사 ID, LINK_UNMATCHED_SONG = 미매칭 ID)", example = "12",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 100) String targetRef,
            @Schema(description = "실행에 필요한 추가 값. LINK_UNMATCHED_SONG 은 songId 필수", example = "{\"songId\":3}",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, Object> payload,
            @Schema(description = "요청 사유", example = "KOPIS 동기화 실패(run:42) 뒤 재생목록이 셋리와 다름",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank @Size(max = 1000) String reason,
            @Schema(description = "근거 ID 목록 (1~20개)", example = "[\"run:42\",\"snapshot:7\"]", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotEmpty @Size(max = 20) List<String> evidenceRefs) {
    }

    @Schema(description = "조치 요청 생성 결과")
    public record CreatedResponse(
            @Schema(description = "요청 ID (중복이면 기존 PENDING 요청 ID)", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
            Long id,
            @Schema(description = "같은 대상의 PENDING 요청이 이미 있어 새로 만들지 않았는지", example = "false",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean duplicate) {

        static CreatedResponse from(Created created) {
            return new CreatedResponse(created.id(), created.duplicate());
        }
    }

    @Schema(description = "조치 요청 (관리자 승인 화면)")
    public record AgentRequestResponse(
            @Schema(description = "요청 ID", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
            Long id,
            @Schema(description = "조치 유형", example = "RETRY_PLAYLIST_SYNC", requiredMode = Schema.RequiredMode.REQUIRED)
            String type,
            @Schema(description = "조치 대상 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
            String targetRef,
            @Schema(description = "추가 값. 없으면 빈 객체", example = "{\"songId\":3}", requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, Object> payload,
            @Schema(description = "요청 사유 (AI 작성, 사실 여부는 근거로 확인)", example = "KOPIS 동기화 실패 뒤 재생목록이 셋리와 다름",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String reason,
            @Schema(description = "근거 ID 목록", example = "[\"run:42\"]", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> evidenceRefs,
            @Schema(description = "상태", example = "PENDING", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"PENDING", "APPROVED", "REJECTED", "EXECUTED", "FAILED"})
            String status,
            @Schema(description = "요청한 서비스 계정과 MCP 클라이언트", example = "jan-mcp:eval-claude", requiredMode = Schema.RequiredMode.REQUIRED)
            String requestedBy,
            @Schema(description = "승인·거절한 관리자 ID. 대기 중이면 null", example = "1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String decidedBy,
            @Schema(description = "승인·거절 시각 (UTC). 대기 중이면 null", example = "2026-11-05T03:00:00Z",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant decidedAt,
            @Schema(description = "요청 시각 (UTC)", example = "2026-11-05T02:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant createdAt) {

        static AgentRequestResponse from(AgentRequestView v) {
            return new AgentRequestResponse(v.id(), v.type(), v.targetRef(), v.payload(), v.reason(), v.evidenceRefs(), v.status(),
                    v.requestedBy(), v.decidedBy(), v.decidedAt(), v.createdAt());
        }
    }
}
