package com.janspace.event.api;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.locale.LocalizedText;
import com.janspace.event.application.EventCommand.EventInput;
import com.janspace.event.application.VenueCommand.VenueInput;
import com.janspace.event.domain.EventStatus;
import com.janspace.event.domain.EventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * 관리자 행사 입력 (프론트 AdminEventInput). 생성은 name·kind·date 필수, 수정(PATCH)은 준 필드만 반영하며 null 은 "변경 없음" 이다.
 * 발견 행사의 초안(AdminSuggestion.draft)도 이 형태로 내린다.
 */
@Schema(description = "관리자 행사 입력. PATCH 에서는 준 필드만 반영")
public record AdminEventInput(

        @Schema(description = "행사 이름 (original 필수, 번역 선택)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalizedText name,

        @Schema(description = "행사 종류", example = "CONCERT", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"CONCERT", "FESTIVAL", "UNIVERSITY", "FREE", "BROADCAST", "OTHER"})
        EventType kind,

        @Schema(description = "행사 날짜 (공연장 현지)", example = "2026-10-23", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalDate date,

        @Schema(description = "시작 시각 (공연장 현지 벽시계, HH:mm)", example = "19:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String startTime,

        @Schema(description = "종료 시각 (공연장 현지 벽시계, HH:mm)", example = "21:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String endTime,

        @Schema(description = "공연장. 이름이 같은 공연장이 있으면 재사용", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid VenueInputDto venue,

        @Schema(description = "시즌·투어 이름", example = "2026 전국투어", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String season,

        @Schema(description = "생명주기 상태", example = "ACTIVE", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"ACTIVE", "CANCEL_SUSPECTED", "CANCELLED"})
        EventStatus status
) {

    @Schema(description = "관리자 공연장 입력")
    public record VenueInputDto(
            @Schema(description = "공연장 이름 (original 필수)", requiredMode = Schema.RequiredMode.REQUIRED)
            LocalizedText name,
            @Schema(description = "도시", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            LocalizedText city,
            @Schema(description = "국가 코드 (ISO 3166-1 alpha-2)", example = "KR", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String countryCode,
            @Schema(description = "좌표. 없으면 지오코딩", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Valid LocationDto location) {

        VenueInput toInput() {
            if (name == null) {
                throw new IllegalArgumentException("venue.name 은 필수입니다");
            }
            return new VenueInput(name, city, countryCode, location == null ? null : location.toPoint());
        }
    }

    EventInput toInput() {
        return new EventInput(name, kind, date, parseTime(startTime), parseTime(endTime),
                venue == null ? null : venue.toInput(), season, status);
    }

    static LocalTime parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalTime.parse(value, EventResponse.HH_MM);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(ErrorCode.INVALID_EVENT_SCHEDULE, "시각은 HH:mm 형식이어야 합니다: " + value);
        }
    }
}
