package com.janspace.ticket.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 티켓 꽂기. 인식 결과를 사용자가 확인·수정한 값만 보낸다 (사진·인식 글자는 보내지 않는다).
 */
@Schema(description = "티켓 꽂기 요청")
public record TicketCreateRequest(
        @Schema(description = "행사 ID (인식 후보에서 사용자가 고른 것)", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long eventId,
        @Schema(description = "좌석 (100자 이하). 없으면 null", example = "1층 A구역 12열 5번", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 100) String seat,
        @Schema(description = "기기가 사진에서 뽑은 포인트 색 (#RRGGBB). 없으면 null", example = "#D94F3D", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Pattern(regexp = TicketSkinUpsertRequest.HEX) String accent,
        @Schema(description = "스킨 ID. 보내지 않으면 오늘 배포 중인 시즌 스킨, 그것도 없으면 기본 스킨(null)", example = "2",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long skinId) {
}
