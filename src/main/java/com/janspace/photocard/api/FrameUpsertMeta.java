package com.janspace.photocard.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.photocard.application.UpsertFrame;
import com.janspace.photocard.domain.FrameKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * PUT /admin/frames 의 meta 파트(application/json). SVG 는 frame 파트로 따로 온다.
 */
@Schema(description = "네컷 프레임 upsert 메타 (multipart 의 meta 파트, Content-Type application/json)")
public record FrameUpsertMeta(
        @Schema(description = "프레임 ID. 있으면 갱신, 없으면 신규", example = "3", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long id,
        @Schema(description = "이름 (평문)", example = "픽셀 우주", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "종류. ORIGINAL 은 기간 없이 상시, SPECIAL 은 availableFrom·availableTo 필수", example = "SPECIAL",
                requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ORIGINAL", "SPECIAL"})
        @NotNull FrameKind kind,
        @Schema(description = "시즌 이름", example = "2026 아시아 투어", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 50) String season,
        @Schema(description = "배포 시작일 (SPECIAL 만)", example = "2026-10-10", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableFrom,
        @Schema(description = "배포 종료일(포함, SPECIAL 만)", example = "2026-12-19", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableTo,
        @Schema(description = "사진 칸 좌표 3~4개 (1080×3240 기준, SVG 의 투명 구멍과 같아야 함). frame 파일과 함께 보내고, 파일 없이 메타만 바꿀 때는 생략",
                example = "[{\"x\":90,\"y\":84,\"w\":900,\"h\":672},{\"x\":90,\"y\":804,\"w\":900,\"h\":672},{\"x\":90,\"y\":1524,\"w\":900,\"h\":672},{\"x\":90,\"y\":2244,\"w\":900,\"h\":672}]",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<@Valid @NotNull SlotRectDto> slotRects) {

    UpsertFrame toCommand() {
        return new UpsertFrame(id, name, kind, season, availableFrom, availableTo,
                slotRects == null ? null : slotRects.stream().map(SlotRectDto::toDomain).toList());
    }
}
