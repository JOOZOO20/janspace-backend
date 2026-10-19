package com.stagelog.attendance.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * GET /records/can-chain 응답.
 */
@Schema(description = "연속 이동 토글 가능 여부")
public record CanChainResponse(
        @Schema(description = "직전 기록이 0~5일 전 다른 도시이고 두 공연장 좌표가 있으면 true", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean eligible,
        @Schema(description = "직전에 간 행사 ID. 없으면 null", example = "11", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long previousEventId) {
}
