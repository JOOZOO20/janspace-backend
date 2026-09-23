package com.janspace.catalog.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "앨범커버 업로드 결과")
public record CoverResponse(
        @Schema(description = "커버 공개 URL", example = "https://cdn.janspace.app/albums/3.png", requiredMode = Schema.RequiredMode.REQUIRED)
        String coverUrl) {
}
