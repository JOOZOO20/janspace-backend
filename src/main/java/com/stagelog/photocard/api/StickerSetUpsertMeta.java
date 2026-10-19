package com.stagelog.photocard.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.photocard.application.UpsertStickerSet;
import com.stagelog.photocard.domain.StickerLabel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * PUT /admin/sticker-sets 의 meta 파트(application/json). 스티커 파일은 stickers 파트로 따로 온다.
 */
@Schema(description = "스티커 세트 upsert 메타 (multipart 의 meta 파트, Content-Type application/json)")
public record StickerSetUpsertMeta(
        @Schema(description = "세트 ID. 있으면 갱신 + 새 파일 추가, 없으면 신규", example = "2", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long id,
        @Schema(description = "세트 이름. original 필수(100자 이하), 번역 키(ko, en, zh-TW, ja) 선택",
                example = "{\"original\":\"빛의 밤\",\"en\":\"Night of Lights\"}", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalizedText name,
        @Schema(description = "original 의 로케일. 생략하면 ko", example = "ko", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 10) String originalLocale,
        @Schema(description = "시즌 이름. 기본 세트면 생략", example = "빛의 밤 2026", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 50) String season,
        @Schema(description = "배포 시작일. 생략하면 시작 제한 없음", example = "2026-11-01", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableFrom,
        @Schema(description = "배포 종료일(포함). 생략하면 상시", example = "2026-12-31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableTo,
        @Schema(description = "기본 세트 여부. 생략하면 false", example = "false", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean isDefault,
        @Schema(description = "이번에 올리는 stickers 파일 각각의 이름·분류. 파일과 같은 순서·같은 개수. 생략하면 라벨 없이 올린다",
                example = "[{\"name\":\"하트\",\"category\":\"heart\",\"group\":\"pattern\"}]", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<@Valid @NotNull NewSticker> newStickers) {

    @Schema(description = "업로드 스티커 하나의 라벨")
    public record NewSticker(
            @Schema(description = "화면 읽기용 이름 (세트 원어 로케일)", example = "하트", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Size(max = 100) String name,
            @Schema(description = "편집기 서랍의 종류", example = "heart", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Size(max = 30) String category,
            @Schema(description = "서랍 안의 묶음", example = "pattern", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Size(max = 30) String group) {

        StickerLabel toDomain() {
            return new StickerLabel(blankToNull(name), blankToNull(category), blankToNull(group));
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    UpsertStickerSet toCommand() {
        return new UpsertStickerSet(id, name, originalLocale, season, availableFrom, availableTo, isDefault,
                newStickers == null ? null : newStickers.stream().map(NewSticker::toDomain).toList());
    }
}
