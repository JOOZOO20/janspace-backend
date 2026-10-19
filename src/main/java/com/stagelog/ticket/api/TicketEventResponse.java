package com.stagelog.ticket.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.event.application.EventView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 티켓에 찍히는 행사 정보. 공연명·날짜·시각·공연장은 티켓 글자가 아니라 행사 값이다.
 */
@Schema(description = "티켓의 행사 (공연명·날짜·시각·공연장은 행사 DB 값)")
public record TicketEventResponse(
        @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "공연명 (원어 + 번역)", example = "{\"original\":\"잔나비 전국투어 - 서울\",\"en\":\"JANNABI Tour - Seoul\"}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalizedText name,
        @Schema(description = "행사 날짜 (공연장 현지). 티켓북 연도 인덱스 기준", example = "2026-09-16", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate date,
        @Schema(description = "시작 시각 (공연장 현지 벽시계, HH:mm). 없으면 null", example = "19:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String startTime,
        @Schema(description = "startTime 의 시간대 (IANA)", example = "Asia/Seoul", requiredMode = Schema.RequiredMode.REQUIRED)
        String timezone,
        @Schema(description = "공연장. 없으면 null",
                example = "{\"name\":{\"original\":\"KSPO DOME\",\"ja\":\"KSPOドーム\"},\"city\":{\"original\":\"서울\"}}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Venue venue) {

    static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Schema(description = "티켓의 공연장")
    public record Venue(
            @Schema(description = "공연장 이름 (원어 + 번역)", example = "{\"original\":\"KSPO DOME\",\"ja\":\"KSPOドーム\"}",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            LocalizedText name,
            @Schema(description = "도시 (원어 + 번역). 없으면 null", example = "{\"original\":\"서울\",\"en\":\"Seoul\"}",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            LocalizedText city) {
    }

    static TicketEventResponse from(EventView view) {
        Venue venue = view.venue() == null ? null : new Venue(view.venue().name(), view.venue().city());
        return new TicketEventResponse(view.id(), view.name(), view.eventDate(),
                view.startsAtLocal() == null ? null : view.startsAtLocal().format(HH_MM), view.timezone(), venue);
    }
}
