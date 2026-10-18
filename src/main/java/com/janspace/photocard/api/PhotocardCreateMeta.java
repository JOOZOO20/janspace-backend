package com.janspace.photocard.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.List;

/**
 * POST /photocards 의 meta 파트(JSON 문자열). 파일은 image 파트로 따로 온다.
 */
@Schema(description = "포토카드 생성 메타 (multipart 의 meta 파트에 JSON 문자열로)")
public record PhotocardCreateMeta(
        @Schema(description = "스티커 배치 목록. 생략하면 빈 배열", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid List<PlacedStickerDto> stickers,
        @Schema(description = "붙일 기록(별) ID. 내 기록이 아니면 404 RECORD_NOT_FOUND. 생략·null 이면 바인더에만", example = "31",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long linkedRecordId) {

    List<PlacedStickerDto> stickersOrEmpty() {
        return stickers == null ? List.of() : stickers;
    }
}
