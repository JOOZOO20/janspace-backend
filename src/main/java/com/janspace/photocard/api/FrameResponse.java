package com.janspace.photocard.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.photocard.application.FrameView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 프론트 FrameSet 계약 + 칸 좌표(slotRects). 칸 좌표는 imageUrl 의 확장자만 .json 으로 바꾼 주소에도 {canvas, slots} 로 있다.
 */
@Schema(description = "네컷 프레임")
public record FrameResponse(
        @Schema(description = "프레임 ID", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "이름 (평문, 번역 없음)", example = "픽셀 우주", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "종류. ORIGINAL = 상시, SPECIAL = 배포 기간 한정", example = "ORIGINAL", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"ORIGINAL", "SPECIAL"})
        String kind,
        @Schema(description = "시즌 이름. 없으면 null", example = "2026 아시아 투어", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String season,
        @Schema(description = "프레임 SVG 공개 URL (사진 칸은 투명, Content-Type image/svg+xml). 같은 경로의 .json 이 칸 좌표",
                example = "https://cdn.janspace.app/frames/5b0c...a1.svg", requiredMode = Schema.RequiredMode.REQUIRED)
        String imageUrl,
        @Schema(description = "사진 칸 수 (응원석 변형은 3)", example = "4", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"3", "4"})
        int slots,
        @Schema(description = "사진 칸 좌표 (1080×3240 기준, 위에서부터)", example = "[{\"x\":90,\"y\":84,\"w\":900,\"h\":672}]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<SlotRectDto> slotRects,
        @Schema(description = "배포 시작일. ORIGINAL 은 null", example = "2026-10-10", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableFrom,
        @Schema(description = "배포 종료일(포함). ORIGINAL 은 null", example = "2026-12-19", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableTo) {

    static FrameResponse from(FrameView view) {
        return new FrameResponse(view.id(), view.name(), view.kind().name(), view.season(), view.imageUrl(), view.slotRects().size(),
                view.slotRects().stream().map(SlotRectDto::from).toList(), view.availableFrom(), view.availableTo());
    }
}
