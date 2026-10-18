package com.janspace.photocard.api;

import com.janspace.photocard.domain.PlacedSticker;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "포토카드 위 스티커 배치 하나. 좌표·크기·회전 단위는 프론트 정의를 그대로 저장·반환한다")
public record PlacedStickerDto(
        @Schema(description = "스티커 ID", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long stickerId,
        @Schema(description = "x 위치", example = "0.42", requiredMode = Schema.RequiredMode.REQUIRED)
        double x,
        @Schema(description = "y 위치", example = "0.65", requiredMode = Schema.RequiredMode.REQUIRED)
        double y,
        @Schema(description = "배율", example = "1.2", requiredMode = Schema.RequiredMode.REQUIRED)
        double scale,
        @Schema(description = "회전 각도", example = "15", requiredMode = Schema.RequiredMode.REQUIRED)
        double rotation) {

    static PlacedStickerDto from(PlacedSticker s) {
        return new PlacedStickerDto(s.stickerId(), s.x(), s.y(), s.scale(), s.rotation());
    }

    PlacedSticker toDomain() {
        return new PlacedSticker(stickerId, x, y, scale, rotation);
    }
}
