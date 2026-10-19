package com.stagelog.ticket.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.ticket.domain.TicketSkin;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 프론트 TicketSkin 계약.
 */
@Schema(description = "티켓 스킨 (종이색·포인트색·글자색)")
public record TicketSkinResponse(
        @Schema(description = "스킨 ID", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "스킨 이름 (평문)", example = "2026 가을 투어", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "시즌 이름. 없으면 null", example = "2026 가을", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String season,
        @Schema(description = "종이색 (#rrggbb)", example = "#f4ecdd", requiredMode = Schema.RequiredMode.REQUIRED)
        String paper,
        @Schema(description = "포인트 색 (#rrggbb)", example = "#d94f3d", requiredMode = Schema.RequiredMode.REQUIRED)
        String accent,
        @Schema(description = "글자색 (#rrggbb)", example = "#2b2118", requiredMode = Schema.RequiredMode.REQUIRED)
        String ink,
        @Schema(description = "배포 시작일. null 이면 시작 제한 없음", example = "2026-10-01", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableFrom,
        @Schema(description = "배포 종료일(포함). null 이면 상시", example = "2026-12-31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableTo,
        @Schema(description = "기본 스킨 여부 (시즌 스킨이 없을 때 쓰는 것, 기간과 무관하게 항상 목록에 나온다)", example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isDefault) {

    static TicketSkinResponse from(TicketSkin skin) {
        return new TicketSkinResponse(skin.getId(), skin.getName(), skin.getSeason(), skin.getPaperColor(), skin.getAccentColor(),
                skin.getInkColor(), skin.getAvailableFrom(), skin.getAvailableUntil(), skin.isDefault());
    }
}
