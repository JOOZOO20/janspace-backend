package com.stagelog.ticket.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.ticket.application.TicketSkinService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 관리자 티켓 스킨 저장. 식별자는 생성 본문이 아닌 수정 경로에서 받는다.
 */
@Schema(description = "티켓 스킨 생성·전체 수정 요청")
public record TicketSkinSaveRequest(
        @Schema(description = "스킨 이름 (평문, 100자 이하)", example = "2026 가을 투어", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "시즌 이름 (50자 이하). 없으면 null", example = "2026 가을", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 50) String season,
        @Schema(description = "종이색 (#RRGGBB)", example = "#F4ECDD", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Pattern(regexp = HEX) String paper,
        @Schema(description = "포인트 색 (#RRGGBB)", example = "#D94F3D", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Pattern(regexp = HEX) String accent,
        @Schema(description = "글자색 (#RRGGBB)", example = "#2B2118", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Pattern(regexp = HEX) String ink,
        @Schema(description = "배포 시작일. null 이면 시작 제한 없음", example = "2026-10-01", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableFrom,
        @Schema(description = "배포 종료일(포함). null 이면 상시. availableFrom 보다 앞이면 400", example = "2026-12-31",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate availableTo,
        @Schema(description = "기본 스킨 여부. true 면 기존 기본 스킨은 내려감. 생략하면 false", example = "false",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean isDefault) {

    static final String HEX = "^#[0-9a-fA-F]{6}$";

    TicketSkinService.Upsert toCommand(Long id) {
        return new TicketSkinService.Upsert(id, name, season, paper, accent, ink, availableFrom, availableTo, Boolean.TRUE.equals(isDefault));
    }
}
