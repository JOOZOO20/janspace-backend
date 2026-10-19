package com.stagelog.event.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.event.application.EventCommand.EventInput;
import com.stagelog.event.application.VenueCommand.VenueInput;
import com.stagelog.event.domain.EventStatus;
import com.stagelog.event.domain.EventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 관리자 행사 입력 (프론트 AdminEventInput). 생성은 name·kind·date 필수, 수정(PATCH)은 준 필드만 반영한다.
 * 기존 필드는 null 도 "변경 없음"이다. ticketUrl·description·runningMinutes 는 출처(KOPIS·Ticketmaster)도 채우는 필드라
 * 키 없음 = 변경 없음, null = 관리자 값을 지우고 출처 값 따르기, 값 = 관리자 값으로 잠금 으로 구분한다 (Optional: 없음 = null, null = empty).
 * 발견 행사의 초안(AdminSuggestion.draft)도 이 형태로 내린다.
 */
@Schema(description = "관리자 행사 입력. PATCH 에서는 준 필드만 반영. 시간대는 입력하지 않는다: 서버가 공연장 좌표로 정하고(없으면 Asia/Seoul) 응답 Event.timezone 으로 내린다")
public record AdminEventInput(

        @Schema(description = "행사 이름 (original 필수, 번역 선택)", example = "{\"original\":\"잔나비 단독 공연\",\"en\":\"Jannabi Solo Concert\"}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalizedText name,

        @Schema(description = "행사 종류", example = "CONCERT", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"CONCERT", "FESTIVAL", "UNIVERSITY", "FREE", "BROADCAST", "OTHER"})
        EventType kind,

        @Schema(description = "행사 날짜 (공연장 현지 날짜, yyyy-MM-dd)", example = "2026-10-23", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate date,

        @Schema(description = "시작 시각 (공연장 현지 벽시계, HH:mm). 시간대는 서버가 공연장 좌표로 정한다(없으면 Asia/Seoul, 응답 Event.timezone)",
                example = "19:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String startTime,

        @Schema(description = "종료 시각 (공연장 현지 벽시계, HH:mm). 시간대는 startTime 과 같다", example = "21:00",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String endTime,

        @Schema(description = "공연장. 이름이 같은 공연장이 있으면 재사용", example = "{\"name\":{\"original\":\"KSPO DOME\"},\"city\":{\"original\":\"서울\"},\"countryCode\":\"KR\",\"location\":{\"lat\":37.5199,\"lng\":127.1233}}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid VenueInputDto venue,

        @Schema(description = "시즌·투어 이름", example = "2026 전국투어", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String season,

        @Schema(description = "생명주기 상태", example = "ACTIVE", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"ACTIVE", "CANCEL_SUSPECTED", "CANCELLED"})
        EventStatus status,

        @Schema(description = "예매 URL (http/https, 2048자 이하). 키 없음 = 변경 없음, null = 관리자 값을 지우고 출처 값 따르기",
                example = "https://tickets.interpark.com/goods/26012345", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<@Size(max = 2048) @Pattern(regexp = "https?://\\S+", message = "http(s) URL 이어야 합니다") String> ticketUrl,

        @Schema(description = "행사 설명 (original 필수, 번역 선택, 각 5000자 이하). 키 없음 = 변경 없음, null = 관리자 값(번역 포함)을 지우고 출처 값 따르기",
                example = "{\"original\":\"출연: 잔나비 / 관람 연령: 8세 이상\",\"en\":\"Cast: JANNABI / Age: 8+\"}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<LocalizedText> description,

        @Schema(description = "공연 시간(분, 1~1440). 종료 시각 초안 계산에만 쓴다. 키 없음 = 변경 없음, null = 관리자 값을 지우고 출처 값 따르기",
                example = "150", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<@Min(1) @Max(1440) Integer> runningMinutes
) {

    static final int DESCRIPTION_MAX_LENGTH = 5000;

    @Schema(description = "관리자 공연장 입력")
    public record VenueInputDto(
            @Schema(description = "공연장 이름 (original 필수)", example = "{\"original\":\"KSPO DOME\",\"ko\":\"KSPO돔\"}",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            LocalizedText name,
            @Schema(description = "도시", example = "{\"original\":\"서울\",\"en\":\"Seoul\"}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            LocalizedText city,
            @Schema(description = "국가 코드 (ISO 3166-1 alpha-2)", example = "KR", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String countryCode,
            @Schema(description = "좌표. 없으면 지오코딩", example = "{\"lat\":37.5199,\"lng\":127.1233}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Valid LocationDto location) {

        VenueInput toInput() {
            if (name == null) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT, "venue.name 은 필수입니다.");
            }
            return new VenueInput(name, city, countryCode, location == null ? null : location.toPoint());
        }
    }

    EventInput toInput() {
        return new EventInput(name, kind, date, parseTime(startTime), parseTime(endTime),
                venue == null ? null : venue.toInput(), season, status, ticketUrl, checkDescription(description), runningMinutes);
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

    // 설명은 TEXT 컬럼(64KB)에 들어가므로 원문·번역 각각 길이를 막는다. 빈 원문은 null(지우기)로 보내야 한다
    private static Optional<LocalizedText> checkDescription(Optional<LocalizedText> description) {
        if (description == null || description.isEmpty()) {
            return description;
        }
        LocalizedText text = description.get();
        boolean tooLong = text.original().length() > DESCRIPTION_MAX_LENGTH
                || text.translations().values().stream().anyMatch(t -> t.length() > DESCRIPTION_MAX_LENGTH);
        if (text.original().isBlank() || tooLong) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT,
                    "description 은 1~" + DESCRIPTION_MAX_LENGTH + "자여야 합니다. 지우려면 null 을 보냅니다.");
        }
        return description;
    }
}
