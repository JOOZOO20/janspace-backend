package com.stagelog.event.api;

import com.stagelog.event.domain.ProposalStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 관리자 API 의 작은 요청 본문 모음.
 */
public final class AdminRequests {

    private AdminRequests() {
    }

    @Schema(description = "잠금 해제 요청 (출처 값 따르기)")
    public record UnlockRequest(
            @Schema(description = "해제할 필드", example = "STARTS_AT", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NAME", "VENUE", "EVENT_DATE", "STARTS_AT", "ENDS_AT", "RUNNING_MINUTES", "TYPE",
                            "SEASON", "POSTER", "TICKET_URL", "DESCRIPTION", "TIMEZONE"})
            @NotBlank String field) {
    }

    @Schema(description = "팬 제안 상태 변경 요청")
    public record SuggestionStatusRequest(
            @Schema(description = "상태", example = "APPLIED", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"PENDING", "APPLIED", "HELD"})
            @NotNull ProposalStatus status) {
    }

    @Schema(description = "발견 행사 수락 요청")
    public record DiscoveryAcceptRequest(
            @Schema(description = "붙일 기존 행사 ID. 없으면 초안으로 새 행사를 만든다", example = "12",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Long eventId) {
    }

    @Schema(description = "행사 번역 등록·수정 요청")
    public record TranslationRequest(
            @Schema(description = "필드", example = "NAME", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"NAME", "DESCRIPTION"})
            @NotBlank String field,
            @Schema(description = "로케일 (BCP-47)", example = "en", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ko", "en", "zh-TW", "ja"})
            @NotBlank String locale,
            @Schema(description = "번역 문자열", example = "Stardust Club Solo Concert", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String text,
            @Schema(description = "검수 완료 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean reviewed) {
    }

    @Schema(description = "관리자 공연장 수정 요청. 준 필드만 반영하며 각 필드는 잠긴다")
    public record VenueUpdateRequest(
            @Schema(description = "이름 (원어)", example = "KSPO DOME", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String name,
            @Schema(description = "도시 (원어)", example = "서울", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String city,
            @Schema(description = "좌표", example = "{\"lat\":37.5199,\"lng\":127.1233}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Valid LocationDto location,
            @Schema(description = "시간대 (IANA)", example = "Asia/Seoul", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String timezone) {
    }

    @Schema(description = "포스터 업로드 결과")
    public record PosterResponse(
            @Schema(description = "포스터 공개 URL", example = "https://cdn.example/events/12/poster.jpg", requiredMode = Schema.RequiredMode.REQUIRED)
            String url) {
    }
}
