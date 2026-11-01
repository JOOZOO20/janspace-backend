package com.stagelog.ticket.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Optional;

/**
 * 티켓 부분 수정. 필드가 없으면 유지, null 이면 비운다 (Optional: 키 없음 = null, "x": null = empty). eventId 는 비울 수 없다.
 */
@Schema(description = "티켓 부분 수정 요청. 보낸 필드만 반영한다")
public record TicketUpdateRequest(
        @Schema(description = "행사 ID (다른 행사로 옮김). null 은 400", example = "13", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<Long> eventId,
        @Schema(description = "좌석 (100자 이하). null 이면 지운다", example = "R석 3열 7번", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<@Size(max = 100) String> seat,
        @Schema(description = "포인트 색 (#RRGGBB). null 이면 지운다", example = "#3D6FD9", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<@Pattern(regexp = TicketSkinSaveRequest.HEX) String> accent,
        @Schema(description = "스킨 ID. null 이면 기본 스킨", example = "2", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<Long> skinId) {
}
