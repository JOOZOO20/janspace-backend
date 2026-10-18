package com.janspace.event.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.event.application.EventSourceQuery.EventSources;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 행사 출처 현황.
 */
@Schema(description = "행사에 연결된 출처·매칭 후보·대기 중 변경")
public record EventSourcesResponse(

        @Schema(description = "확정 연결된 출처", requiredMode = Schema.RequiredMode.REQUIRED)
        List<SourceResponse> sources,

        @Schema(description = "매칭 후보 (둘 이상일 때 관리자가 고른다)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<CandidateResponse> candidates,

        @Schema(description = "대기 중(PENDING) 변경 요청", requiredMode = Schema.RequiredMode.REQUIRED)
        List<SourceChangeResponse> pendingChanges,

        @Schema(description = "종료 시각 초안 (starts_at_local + running_minutes, 공연장 현지 벽시계). 저장되지 않은 제안값",
                example = "2026-10-23T21:00:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime endsAtDraft,

        @Schema(description = "endsAtDraft 의 시간대 (IANA)", example = "Asia/Seoul", requiredMode = Schema.RequiredMode.REQUIRED)
        String timezone
) {

    @Schema(description = "확정 연결된 출처")
    public record SourceResponse(
            @Schema(description = "제공자", example = "KOPIS", requiredMode = Schema.RequiredMode.REQUIRED)
            String provider,
            @Schema(description = "외부 ID", example = "PF178134", requiredMode = Schema.RequiredMode.REQUIRED)
            String externalId,
            @Schema(description = "회차 키", example = "2026-10-23#19:00", requiredMode = Schema.RequiredMode.REQUIRED)
            String occurrenceKey,
            @Schema(description = "마지막 수집 시각 (UTC)", example = "2026-09-24T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant fetchedAt) {
    }

    @Schema(description = "매칭 후보")
    public record CandidateResponse(
            @Schema(description = "후보 ID", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
            Long id,
            @Schema(description = "제공자", example = "KOPIS", requiredMode = Schema.RequiredMode.REQUIRED)
            String provider,
            @Schema(description = "외부 ID", example = "PF178135", requiredMode = Schema.RequiredMode.REQUIRED)
            String externalId,
            @Schema(description = "회차 키", example = "2026-10-23", requiredMode = Schema.RequiredMode.REQUIRED)
            String occurrenceKey) {
    }

    static EventSourcesResponse from(EventSources sources, String timezone) {
        return new EventSourcesResponse(
                sources.sources().stream().map(s -> new SourceResponse(s.getProvider().name(), s.getExternalId(),
                        s.getOccurrenceKey(), s.getFetchedAt())).toList(),
                sources.candidates().stream().map(c -> new CandidateResponse(c.getId(), c.getProvider().name(),
                        c.getExternalId(), c.getOccurrenceKey())).toList(),
                sources.pendingChanges().stream().map(SourceChangeResponse::from).toList(),
                sources.endsAtDraft(), timezone);
    }
}
