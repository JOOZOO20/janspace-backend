package com.stagelog.photocard.api;

import com.stagelog.photocard.domain.PlacedSticker;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * stickerId 는 스티커 테이블을 참조하지 않는다. 앱이 직접 그리는 코드 스티커(code:*)가 섞이고, 세트에서 지운 스티커도
 * 이미 만든 카드의 배치에는 남아야 하기 때문이다. 대신 모양(숫자 ID 또는 code:)과 길이만 검증한다.
 */
@Schema(description = "포토카드 위 스티커 배치 하나. 좌표·크기·회전 단위는 프론트 정의를 그대로 저장·반환한다")
public record PlacedStickerDto(
        @Schema(description = "스티커 ID. 서버 스티커면 숫자 ID 문자열, 앱이 그리는 코드 스티커면 code:* (예 code:star:HAPPY, code:date:retro:ymd:2026-10-23)",
                example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Size(max = 100) @Pattern(regexp = "\\d{1,19}|code:[A-Za-z0-9:._-]+", message = "stickerId 는 숫자 ID 또는 code:* 여야 합니다.")
        String stickerId,
        @Schema(description = "x 위치 (장면 좌표, 1080 기준)", example = "540", requiredMode = Schema.RequiredMode.REQUIRED)
        double x,
        @Schema(description = "y 위치 (장면 좌표)", example = "675", requiredMode = Schema.RequiredMode.REQUIRED)
        double y,
        @Schema(description = "배율", example = "1.2", requiredMode = Schema.RequiredMode.REQUIRED)
        double scale,
        @Schema(description = "회전 각도 (도)", example = "15", requiredMode = Schema.RequiredMode.REQUIRED)
        double rotation) {

    static PlacedStickerDto from(PlacedSticker s) {
        return new PlacedStickerDto(s.stickerId(), s.x(), s.y(), s.scale(), s.rotation());
    }

    PlacedSticker toDomain() {
        return new PlacedSticker(stickerId, x, y, scale, rotation);
    }
}
