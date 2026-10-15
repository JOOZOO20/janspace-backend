package com.janspace.photocard.api;

import com.janspace.photocard.domain.SlotRect;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "네컷 프레임의 사진 칸 하나 (1080×3240 프레임 좌표, px)")
public record SlotRectDto(
        @Schema(description = "왼쪽 x", example = "90", requiredMode = Schema.RequiredMode.REQUIRED)
        int x,
        @Schema(description = "위쪽 y", example = "84", requiredMode = Schema.RequiredMode.REQUIRED)
        int y,
        @Schema(description = "폭", example = "900", requiredMode = Schema.RequiredMode.REQUIRED)
        int w,
        @Schema(description = "높이", example = "672", requiredMode = Schema.RequiredMode.REQUIRED)
        int h) {

    static SlotRectDto from(SlotRect r) {
        return new SlotRectDto(r.x(), r.y(), r.w(), r.h());
    }

    SlotRect toDomain() {
        return new SlotRect(x, y, w, h);
    }
}
