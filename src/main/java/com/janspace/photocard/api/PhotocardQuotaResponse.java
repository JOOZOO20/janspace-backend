package com.janspace.photocard.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.photocard.application.PhotocardQuota;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "이번 달 포토카드 제한 (UTC 월 기준, 카드·네컷 합산, 지운 장은 세지 않음)")
public record PhotocardQuotaResponse(
        @Schema(description = "월 제한 장 수", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
        int limit,
        @Schema(description = "이번 달 남은 장 수. 0 이면 생성이 429 PHOTOCARD_LIMIT", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
        int remaining,
        @Schema(description = "제한이 다시 차는 시각 (다음 달 1일 00:00 UTC)", example = "2026-11-01T00:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant resetsAt) {

    static PhotocardQuotaResponse from(PhotocardQuota quota) {
        return new PhotocardQuotaResponse(quota.limit(), quota.remaining(), quota.resetsAt());
    }
}
