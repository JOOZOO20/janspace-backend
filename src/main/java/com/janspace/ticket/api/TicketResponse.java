package com.janspace.ticket.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.ticket.application.TicketView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 티켓북의 티켓 한 장.
 */
@Schema(description = "티켓")
public record TicketResponse(
        @Schema(description = "티켓 ID", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "티켓북 안 연번 (등록 순, 1부터). 앞 티켓을 지우면 뒤 번호가 당겨진다", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        int serial,
        @Schema(description = "행사",
                example = "{\"id\":12,\"name\":{\"original\":\"잔나비 전국투어 - 서울\"},\"date\":\"2026-09-16\",\"startTime\":\"19:00\",\"timezone\":\"Asia/Seoul\",\"venue\":{\"name\":{\"original\":\"KSPO DOME\",\"ja\":\"KSPOドーム\"},\"city\":{\"original\":\"서울\"}}}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        TicketEventResponse event,
        @Schema(description = "같은 행사의 내 기록 ID (조회 시점에 연결). 기록이 없으면 null", example = "31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long recordId,
        @Schema(description = "좌석. 없으면 null", example = "1층 A구역 12열 5번", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String seat,
        @Schema(description = "기기가 사진에서 뽑은 포인트 색 (#rrggbb). 없으면 null. 스킨이 지정되면 스킨 색이 우선", example = "#d94f3d",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String accent,
        @Schema(description = "스킨 ID. null 이면 기본 스킨", example = "2", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long skinId,
        @Schema(description = "꽂은 시각 (UTC)", example = "2026-09-17T13:05:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt) {

    static TicketResponse from(TicketView view) {
        return new TicketResponse(view.ticket().getId(), view.serial(), TicketEventResponse.from(view.event()), view.recordId(),
                view.ticket().getSeat(), view.ticket().getAccentColor(), view.ticket().getSkinId(), view.ticket().getCreatedAt());
    }
}
