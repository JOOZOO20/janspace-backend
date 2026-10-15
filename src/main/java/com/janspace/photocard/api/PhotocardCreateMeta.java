package com.janspace.photocard.api;

import com.janspace.photocard.application.CreatePhotocard;
import com.janspace.photocard.domain.PhotocardKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * POST /photocards 의 meta 파트(application/json). 완성 이미지는 image 파트로 따로 온다.
 */
@Schema(description = "포토카드 생성 메타 (multipart 의 meta 파트, Content-Type application/json)")
public record PhotocardCreateMeta(
        @Schema(description = "종류. 생략하면 CARD. CARD 는 4:5, FOURCUT(네컷) 은 1:3 이미지", example = "FOURCUT",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, allowableValues = {"CARD", "FOURCUT"})
        PhotocardKind kind,
        @Schema(description = "캔버스 배경색 #RRGGBB", example = "#1B2A4A", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "backgroundColor 는 #RRGGBB 여야 합니다.")
        String backgroundColor,
        @Schema(description = "네컷에 쓴 프레임 ID. FOURCUT 에만, 프레임 없이 만들었으면 생략. 없는 ID 는 404 FRAME_NOT_FOUND", example = "3",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long frameId,
        @Schema(description = "스티커 배치 목록(최대 100개). 생략하면 빈 배열",
                example = "[{\"stickerId\":\"7\",\"x\":540,\"y\":675,\"scale\":1.2,\"rotation\":15},{\"stickerId\":\"code:star:HAPPY\",\"x\":200,\"y\":300,\"scale\":1,\"rotation\":0}]",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 100) List<@Valid PlacedStickerDto> stickers,
        @Schema(description = "붙일 기록(별) ID. 내 기록이 아니면 404 RECORD_NOT_FOUND, 다른 카드가 붙어 있으면 409 PHOTOCARD_ALREADY_LINKED. 생략·null 이면 바인더에만",
                example = "31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long linkedRecordId) {

    CreatePhotocard toCommand() {
        return new CreatePhotocard(kind, backgroundColor, frameId,
                stickers == null ? List.of() : stickers.stream().map(PlacedStickerDto::toDomain).toList(), linkedRecordId);
    }
}
