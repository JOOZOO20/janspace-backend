package com.stagelog.media.api;

import com.stagelog.media.application.MediaOwnerType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Schema(description = "기존 원본 백필 요청")
public record BackfillRequest(
        @Schema(description = "원본 소유자 종류", example = "EVENT_POSTER", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"EVENT_POSTER", "ALBUM", "PHOTOCARD", "FOURCUT"})
        @NotNull MediaOwnerType ownerType,
        @Schema(description = "이번 실행에서 새로 등록할 최대 원본 수 (1~100000). 이미 등록된 원본은 세지 않고 건너뛴다", example = "10000",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @Min(1) @Max(100_000) int limit) {
}
