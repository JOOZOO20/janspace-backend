package com.janspace.attendance.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.common.locale.LocalizedText;
import com.janspace.event.application.EventSnapshot;
import com.janspace.event.application.EventView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * 프론트 Constellation 계약: 행사 하나 = 별자리 하나, 별 수 = 기록자 수. 익명이며 높이 축은 없다.
 * 이름·도시는 EventView(번역 포함), 좌표는 EventSnapshot 에서 가져온다 (attendance 는 event.domain 의 GeoPoint 를 볼 수 없다).
 */
@Schema(description = "행사별 별자리")
public record ConstellationResponse(
        @Schema(description = "행사 요약", example = "{\"id\":12,\"name\":{\"original\":\"잔나비 전국투어 서울\",\"en\":\"JANNABI Tour Seoul\"},\"date\":\"2026-10-23\","
                        + "\"venue\":{\"name\":{\"original\":\"KSPO DOME\",\"ko\":\"KSPO 돔\"},\"city\":{\"original\":\"서울\",\"en\":\"Seoul\"},"
                        + "\"countryCode\":\"KR\",\"location\":{\"lat\":37.520,\"lng\":127.126}}}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        EventSummary event,
        @Schema(description = "별 수 = 이 행사를 기록한 사용자 수", example = "412", requiredMode = Schema.RequiredMode.REQUIRED)
        long starCount) {

    @Schema(description = "별자리의 행사 (Event 의 id, name, date, venue)")
    public record EventSummary(
            @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
            Long id,
            @Schema(description = "행사 이름 (원어 + 번역)", example = "{\"original\":\"잔나비 전국투어 서울\",\"en\":\"JANNABI Tour Seoul\"}",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            LocalizedText name,
            @Schema(description = "행사 날짜 (공연장 현지)", example = "2026-10-23", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            @Schema(description = "공연장. 없으면 null", example = "{\"name\":{\"original\":\"KSPO DOME\",\"ko\":\"KSPO 돔\"},\"city\":{\"original\":\"서울\",\"en\":\"Seoul\"},"
                            + "\"countryCode\":\"KR\",\"location\":{\"lat\":37.520,\"lng\":127.126}}",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            VenueSummary venue) {
    }

    @Schema(description = "공연장 (프론트 Venue 계약)")
    public record VenueSummary(
            @Schema(description = "공연장 이름 (원어 + 번역)", example = "{\"original\":\"KSPO DOME\",\"ko\":\"KSPO 돔\"}", requiredMode = Schema.RequiredMode.REQUIRED)
            LocalizedText name,
            @Schema(description = "도시 (원어 + 번역). 없으면 null", example = "{\"original\":\"서울\",\"en\":\"Seoul\"}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            LocalizedText city,
            @Schema(description = "국가 코드. 없으면 null", example = "KR", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String countryCode,
            @Schema(description = "좌표. 없으면 null", example = "{\"lat\":37.520,\"lng\":127.126}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Location location) {
    }

    @Schema(description = "위경도 좌표")
    public record Location(
            @Schema(description = "위도", example = "37.520", requiredMode = Schema.RequiredMode.REQUIRED) double lat,
            @Schema(description = "경도", example = "127.126", requiredMode = Schema.RequiredMode.REQUIRED) double lng) {
    }

    static ConstellationResponse from(EventView view, EventSnapshot snapshot, long starCount) {
        VenueSummary venue = null;
        if (view.venue() != null) {
            EventSnapshot.VenueSnapshot coords = snapshot == null ? null : snapshot.venue();
            Location location = coords == null || coords.lat() == null || coords.lng() == null ? null
                    : new Location(coords.lat(), coords.lng());
            venue = new VenueSummary(view.venue().name(), view.venue().city(), view.venue().countryCode(), location);
        }
        return new ConstellationResponse(new EventSummary(view.id(), view.name(), view.eventDate(), venue), starCount);
    }
}
