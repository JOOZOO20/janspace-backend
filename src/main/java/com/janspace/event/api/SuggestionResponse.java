package com.janspace.event.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.event.domain.EventProposal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 프론트 Suggestion 계약 (팬 제안).
 */
@Schema(description = "팬 제안")
public record SuggestionResponse(

        @Schema(description = "제안 ID", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long eventId,

        @Schema(description = "제안 내용", example = "시작 시각이 19:30 으로 바뀌었어요", requiredMode = Schema.RequiredMode.REQUIRED)
        String text,

        @Schema(description = "처리 상태", example = "PENDING", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"PENDING", "APPLIED", "HELD"})
        String status,

        @Schema(description = "등록 시각 (UTC)", example = "2026-09-24T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt
) {

    static SuggestionResponse from(EventProposal proposal) {
        return new SuggestionResponse(proposal.getId(), proposal.getEvent().getId(), proposal.getContent(),
                proposal.getStatus().name(), proposal.getCreatedAt());
    }
}
