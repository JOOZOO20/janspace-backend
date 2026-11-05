package com.stagelog.ops.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.stagelog.ops.domain.AdminAuditLog;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 관리자 감사 로그 항목. before·after 는 저장된 JSON 객체를 그대로 내린다.
 */
@Schema(description = "관리자 조치 감사 로그 항목")
public record AuditLogResponse(

        @Schema(description = "감사 로그 ID", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "조치 주체 종류", example = "ADMIN", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"ADMIN", "MCP_AGENT", "SYSTEM"})
        String actorType,

        @Schema(description = "조치 주체 식별자 (ADMIN 이면 관리자 사용자 ID)", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        String actorId,

        @Schema(description = "조치 이름", example = "PROPOSAL_APPROVE", requiredMode = Schema.RequiredMode.REQUIRED)
        String action,

        @Schema(description = "대상 종류", example = "SOURCE_CHANGE", requiredMode = Schema.RequiredMode.REQUIRED)
        String targetType,

        @Schema(description = "대상 ID", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        String targetId,

        @Schema(description = "바뀐 필드의 이전 값 (JSON 객체). 없으면 null", example = "{\"status\":\"PENDING\"}",
                type = "object", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonRawValue
        String before,

        @Schema(description = "바뀐 필드의 이후 값 (JSON 객체). 없으면 null", example = "{\"status\":\"APPLIED\"}",
                type = "object", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonRawValue
        String after,

        @Schema(description = "조치 사유. 없으면 null", example = "MCP 요청 #7 승인", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String reason,

        @Schema(description = "요청 ID (X-Request-Id). 요청 밖 조치면 null", example = "3f2a9c1e-5b7d-4e8f-9a0b-1c2d3e4f5a6b",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String requestId,

        @Schema(description = "기록 시각 (UTC)", example = "2026-11-05T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt
) {

    static AuditLogResponse from(AdminAuditLog log) {
        return new AuditLogResponse(log.getId(), log.getActorType(), log.getActorId(), log.getAction(), log.getTargetType(),
                log.getTargetId(), log.getBeforeJson(), log.getAfterJson(), log.getReason(), log.getRequestId(), log.getCreatedAt());
    }
}
