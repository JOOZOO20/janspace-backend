package com.janspace.photocard.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.common.locale.LocalizedText;
import com.janspace.photocard.application.StickerSetView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 프론트 StickerSet 계약. isDefault 는 관리자 화면용으로 덧붙인다.
 */
@Schema(description = "스티커 세트")
public record StickerSetResponse(
        @Schema(description = "세트 ID", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "세트 이름 (원어 + 로케일별 번역)", example = "{\"original\":\"합창의 밤\",\"ko\":\"합창의 밤\",\"en\":\"Choir Night\"}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalizedText name,
        @Schema(description = "시즌 이름. 기본 세트면 null", example = "합창의 밤 2026", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String season,
        @Schema(description = "배포 시작일. null 이면 시작 제한 없음", example = "2026-11-01", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableFrom,
        @Schema(description = "배포 종료일(포함). null 이면 상시", example = "2026-12-31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableTo,
        @Schema(description = "기본 세트 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isDefault,
        @Schema(description = "스티커 목록 (업로드 순)",
                example = "[{\"id\":7,\"setId\":2,\"imageUrl\":\"https://cdn.janspace.app/stickers/2/9a0b...c1.png\",\"name\":{\"original\":\"하트\",\"ko\":\"하트\"},\"category\":\"heart\",\"group\":\"pattern\"}]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<StickerResponse> stickers) {

    @Schema(description = "스티커 하나")
    public record StickerResponse(
            @Schema(description = "스티커 ID", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
            Long id,
            @Schema(description = "소속 세트 ID", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
            Long setId,
            @Schema(description = "이미지 공개 URL (CDN)", example = "https://cdn.janspace.app/stickers/2/9a0b...c1.png", requiredMode = Schema.RequiredMode.REQUIRED)
            String imageUrl,
            @Schema(description = "화면 읽기용 이름 (세트 원어 로케일). 없으면 null", example = "{\"original\":\"하트\",\"ko\":\"하트\"}",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            LocalizedText name,
            @Schema(description = "편집기 서랍의 종류. 없으면 null", example = "heart", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String category,
            @Schema(description = "서랍 안의 묶음. 없으면 null(세트 이름으로 묶임)", example = "pattern", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String group) {
    }

    static StickerSetResponse from(StickerSetView view) {
        return new StickerSetResponse(view.id(), view.name(), view.season(), view.availableFrom(), view.availableTo(), view.isDefault(),
                view.stickers().stream().map(s -> new StickerResponse(s.id(), s.setId(), s.imageUrl(), s.name(), s.category(), s.group())).toList());
    }
}
