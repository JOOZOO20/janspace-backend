package com.janspace.ops.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.ops.domain.SyncRun;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 관리자 동기화 이력 항목. 프론트 SyncLog 계약(id, source, status, ranAt, message)에 finishedAt 을 더한다.
 */
@Schema(description = "동기화 실행 이력 항목")
public record SyncLogResponse(

        @Schema(description = "이력 ID", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "동기화 출처", example = "KOPIS", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"NAVER", "KOPIS", "TICKETMASTER", "SETLISTFM", "YOUTUBE"})
        String source,

        @Schema(description = "실행 상태", example = "OK", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"RUNNING", "OK", "FAILED"})
        String status,

        @Schema(description = "실행 시작 시각 (UTC)", example = "2026-09-24T03:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant ranAt,

        @Schema(description = "실행 종료 시각 (UTC). 실행 중이면 null", example = "2026-09-24T03:00:12Z",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant finishedAt,

        @Schema(description = "결과 요약 또는 실패 사유. 없으면 null", example = "행사 12건 갱신",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String message
) {

    static SyncLogResponse from(SyncRun run) {
        return new SyncLogResponse(run.getId(), run.getSource().name(), run.getStatus().name(),
                run.getStartedAt(), run.getFinishedAt(), run.getSummary());
    }
}
