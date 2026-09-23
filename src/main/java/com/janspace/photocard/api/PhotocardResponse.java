package com.janspace.photocard.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.photocard.application.PhotocardView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 프론트 Photocard 계약.
 */
@Schema(description = "포토카드 한 장")
public record PhotocardResponse(
        @Schema(description = "포토카드 ID", example = "15", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "이미지 공개 URL (CDN)", example = "https://cdn.janspace.app/photocards/2/3f1c...e9.png", requiredMode = Schema.RequiredMode.REQUIRED)
        String imageUrl,
        @Schema(description = "스티커 배치 목록 (저장한 그대로)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<PlacedStickerDto> stickers,
        @Schema(description = "붙인 기록(별) ID. 안 붙였거나 기록이 삭제됐으면 null", example = "31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long linkedRecordId,
        @Schema(description = "생성 시각 (UTC)", example = "2026-10-23T12:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt) {

    static PhotocardResponse from(PhotocardView view) {
        return new PhotocardResponse(view.id(), view.imageUrl(), view.stickers().stream().map(PlacedStickerDto::from).toList(),
                view.linkedRecordId(), view.createdAt());
    }
}
