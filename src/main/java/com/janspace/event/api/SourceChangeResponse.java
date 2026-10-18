package com.janspace.event.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.event.domain.EventSourceChange;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 민감 필드 변경 확인 큐 항목.
 */
@Schema(description = "출처 변경 확인 요청")
public record SourceChangeResponse(

        @Schema(description = "변경 요청 ID", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long eventId,

        @Schema(description = "필드", example = "EVENT_DATE", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"EVENT_DATE", "STARTS_AT", "VENUE"})
        String field,

        @Schema(description = "현재 값 (EVENT_DATE 는 날짜 또는 회차 키, STARTS_AT 은 현지 벽시계 ISO, VENUE 는 공연장 ID)",
                example = "2026-10-23", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String currentValue,

        @Schema(description = "제안 값", example = "2026-10-24", requiredMode = Schema.RequiredMode.REQUIRED)
        String proposedValue,

        @Schema(description = "제안한 제공자", example = "KOPIS", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"NAVER", "KOPIS", "TICKETMASTER", "SETLISTFM"})
        String provider,

        @Schema(description = "감지 시각 (UTC)", example = "2026-09-24T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant detectedAt,

        @Schema(description = "상태", example = "PENDING", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"PENDING", "APPLIED", "REJECTED"})
        String status,

        @Schema(description = "처리 시각 (UTC). 대기 중이면 null", example = "2026-09-25T01:00:00Z",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant resolvedAt
) {

    static SourceChangeResponse from(EventSourceChange change) {
        return new SourceChangeResponse(change.getId(), change.getEvent().getId(), change.getField().name(),
                change.getCurrentValue(), change.getProposedValue(), change.getProvider().name(), change.getDetectedAt(),
                change.getStatus().name(), change.getResolvedAt());
    }
}
