package com.stagelog.event.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.event.api.AdminEventInput.VenueInputDto;
import com.stagelog.event.application.DiscoveryUseCase.DiscoveryView;
import com.stagelog.event.domain.EventProposal;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Optional;

/**
 * 프론트 AdminSuggestion 계약: FAN(팬 제안) 또는 제공자 발견 행사(SETLISTFM_EVENT / KOPIS_EVENT / TICKETMASTER_EVENT / NAVER_EVENT).
 * kind 에 따라 suggestion 또는 externalId·draft·status 가 채워진다.
 */
@Schema(description = "관리자 제안 목록 항목 (팬 제안 + 발견 행사)")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminSuggestionResponse(

        @Schema(description = "종류", example = "FAN", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"FAN", "NAVER_EVENT", "KOPIS_EVENT", "TICKETMASTER_EVENT", "SETLISTFM_EVENT"})
        String kind,

        @Schema(description = "ID (FAN 은 제안 ID, 그 외는 발견 ID)", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "팬 제안 (kind=FAN 일 때만)",
                example = "{\"id\":7,\"eventId\":12,\"text\":\"공연장이 바뀌었어요\",\"status\":\"PENDING\",\"createdAt\":\"2026-09-24T03:00:00Z\"}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        SuggestionResponse suggestion,

        @Schema(description = "제공자 외부 ID (발견일 때만)", example = "PF178134", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String externalId,

        @Schema(description = "발견 회차 키 (발견일 때만)", example = "2026-10-23#19:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String occurrenceKey,

        @Schema(description = "행사 초안 (발견일 때만)",
                example = "{\"name\":{\"original\":\"잔나비 부산 공연\"},\"kind\":\"CONCERT\",\"date\":\"2026-11-01\",\"startTime\":\"19:00\",\"venue\":{\"name\":{\"original\":\"벡스코\"}}}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        AdminEventInput draft,

        @Schema(description = "상태. FAN 은 PENDING/APPLIED/HELD, 발견은 PENDING/ACCEPTED/DISMISSED", example = "PENDING",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String status,

        @Schema(description = "등록 시각 (UTC)", example = "2026-09-24T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt
) {

    static AdminSuggestionResponse fan(EventProposal proposal) {
        return new AdminSuggestionResponse("FAN", proposal.getId(), SuggestionResponse.from(proposal), null, null, null,
                proposal.getStatus().name(), proposal.getCreatedAt());
    }

    static AdminSuggestionResponse discovery(DiscoveryView view) {
        SourceSnapshot s = view.snapshot();
        VenueInputDto venue = s.venue() == null || s.venue().name() == null ? null
                : new VenueInputDto(LocalizedText.of(s.venue().name()),
                        s.venue().city() == null ? null : LocalizedText.of(s.venue().city()),
                        s.venue().countryCode(), LocationDto.from(s.venue().location()));
        // 종류는 출처가 주지 않으므로 CONCERT 초안. 종료 시각은 출처가 제안하지 않는다
        AdminEventInput draft = new AdminEventInput(LocalizedText.of(s.name() == null ? "" : s.name()), EventType.CONCERT,
                s.eventDate(), EventResponse.time(s.startsAtLocal()), null, venue, null, null,
                Optional.ofNullable(s.ticketUrl()), Optional.ofNullable(s.description()).map(LocalizedText::of),
                Optional.ofNullable(s.runningMinutes()));
        return new AdminSuggestionResponse(view.provider().name() + "_EVENT", view.id(), null, view.externalId(),
                view.occurrenceKey(), draft, view.status().name(), view.createdAt());
    }
}
