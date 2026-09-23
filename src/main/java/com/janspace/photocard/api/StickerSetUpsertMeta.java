package com.janspace.photocard.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.common.locale.LocalizedText;
import com.janspace.photocard.application.UpsertStickerSet;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * PUT /admin/sticker-sets 의 meta 파트(JSON 문자열). 스티커 파일은 stickers 파트로 따로 온다.
 */
@Schema(description = "스티커 세트 upsert 메타 (multipart 의 meta 파트에 JSON 문자열로)")
public record StickerSetUpsertMeta(
        @Schema(description = "세트 ID. 있으면 갱신 + 새 파일 추가, 없으면 신규", example = "2", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long id,
        @Schema(description = "세트 이름. original 필수, 번역 키(ko, en, zh-TW, ja) 선택", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalizedText name,
        @Schema(description = "original 의 로케일. 생략하면 ko", example = "ko", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String originalLocale,
        @Schema(description = "시즌 이름. 기본 세트면 생략", example = "합창의 밤 2026", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String season,
        @Schema(description = "배포 시작일. 생략하면 시작 제한 없음", example = "2026-11-01", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableFrom,
        @Schema(description = "배포 종료일(포함). 생략하면 상시", example = "2026-12-31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableTo,
        @Schema(description = "기본 세트 여부. 생략하면 false", example = "false", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean isDefault) {

    UpsertStickerSet toCommand() {
        return new UpsertStickerSet(id, name, originalLocale, season, availableFrom, availableTo, isDefault);
    }
}
