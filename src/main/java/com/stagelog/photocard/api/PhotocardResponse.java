package com.stagelog.photocard.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.photocard.application.PhotocardView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 프론트 Photocard 계약.
 */
@Schema(description = "포토카드 한 장 (카드 또는 네컷)")
public record PhotocardResponse(
        @Schema(description = "포토카드 ID", example = "15", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "종류. CARD = 4:5 포토카드, FOURCUT = 1:3 네컷", example = "CARD", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"CARD", "FOURCUT"})
        String kind,
        @Schema(description = "완성 이미지 공개 URL (CDN). 네컷은 기기에서 4칸+프레임을 합성한 한 장", example = "https://cdn.stagelog.app/photocards/2/3f1c...e9.png",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String imageUrl,
        @Schema(description = "저장한 캔버스 배경색 #RRGGBB. 없으면 null", example = "#1B2A4A", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String backgroundColor,
        @Schema(description = "네컷에 쓴 프레임 ID. 카드이거나 프레임 없이 만들었으면 null", example = "3", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long frameId,
        @Schema(description = "스티커 배치 목록 (저장한 그대로)", example = "[{\"stickerId\":\"7\",\"x\":540,\"y\":675,\"scale\":1.2,\"rotation\":15}]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<PlacedStickerDto> stickers,
        @Schema(description = "붙인 기록(별) ID. 안 붙였거나 기록이 삭제됐으면 null", example = "31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long linkedRecordId,
        @Schema(description = "생성 시각 (UTC)", example = "2026-10-23T12:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt) {

    static PhotocardResponse from(PhotocardView view) {
        return new PhotocardResponse(view.id(), view.kind().name(), view.imageUrl(), view.backgroundColor(), view.frameId(),
                view.stickers().stream().map(PlacedStickerDto::from).toList(), view.linkedRecordId(), view.createdAt());
    }
}
