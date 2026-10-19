package com.stagelog.event.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.event.application.EventView;
import com.stagelog.event.application.port.AttendanceSummaryPort.MySummary;
import com.stagelog.event.domain.EventStatus;
import com.stagelog.event.domain.Provider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 프론트 Event 계약. status 는 생명주기(취소·취소 의심)와 완성도(공연장 좌표·종료 시각 유무)를 합성한 파생 상태.
 * playlistUrl 은 setlist 의 PlaylistUrlPort 가, starCount·my 는 AttendanceSummaryPort 가 채우며 비로그인이면 my 는 null 이다.
 */
@Schema(description = "행사 (프론트 Event 계약)")
public record EventResponse(

        @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,

        @Schema(description = "행사 이름 (원어 + 번역)", example = "{\"original\":\"스타더스트 클럽 단독 공연\",\"en\":\"Stardust Club Solo Concert\"}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalizedText name,

        @Schema(description = "행사 종류", example = "CONCERT", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"CONCERT", "FESTIVAL", "UNIVERSITY", "FREE", "BROADCAST", "OTHER"})
        String kind,

        @Schema(description = "표시 상태 (서버 합성). 취소 계열은 생명주기, 그 외는 공연장 좌표와 종료 시각이 모두 있으면 COMPLETE",
                example = "COMPLETE", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"INCOMPLETE", "COMPLETE", "CANCEL_SUSPECTED", "CANCELLED"})
        String status,

        @Schema(description = "행사 날짜 (공연장 현지)", example = "2026-10-23", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate date,

        @Schema(description = "시작 시각 (공연장 현지 벽시계, HH:mm). 없으면 null", example = "19:00",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String startTime,

        @Schema(description = "종료 시각 (공연장 현지 벽시계, HH:mm). 없으면 null", example = "21:00",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String endTime,

        @Schema(description = "startTime·endTime 의 시간대 (IANA)", example = "Asia/Seoul", requiredMode = Schema.RequiredMode.REQUIRED)
        String timezone,

        @Schema(description = "공연장. 없으면 null",
                example = "{\"id\":3,\"name\":{\"original\":\"KSPO DOME\",\"ko\":\"KSPO돔\"},\"city\":{\"original\":\"서울\"},\"countryCode\":\"KR\",\"location\":{\"lat\":37.5199,\"lng\":127.1233}}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        VenueResponse venue,

        @Schema(description = "시즌·투어 이름. 없으면 null", example = "2026 전국투어", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String season,

        @Schema(description = "외부 출처 ID",
                example = "{\"naverScheduleId\":\"1234567\",\"setlistFmId\":null,\"kopisId\":\"PF178134\",\"ticketmasterId\":null}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        SourceIds source,

        @Schema(description = "포스터 공개 URL. 없으면 null", example = "https://cdn.example/events/12/poster.jpg",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String posterUrl,

        @Schema(description = "예매 URL. 없으면 null", example = "https://tickets.interpark.com/goods/26012345",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String ticketUrl,

        @Schema(description = "행사 설명 (원어 + 번역, 출연진·관람 연령·가격 등). 없으면 null",
                example = "{\"original\":\"출연: 스타더스트 클럽 / 관람 연령: 8세 이상\",\"en\":\"Cast: STARDUST CLUB / Age: 8+\"}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalizedText description,

        @Schema(description = "공연 시간(분). 없으면 null", example = "150", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer runningMinutes,

        @Schema(description = "유튜브 재생목록 URL. 재생목록이 없으면 null", example = "https://www.youtube.com/playlist?list=PLx",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String playlistUrl,

        @Schema(description = "별(기록) 수 = 이 행사를 기록한 사용자 수", example = "412", requiredMode = Schema.RequiredMode.REQUIRED)
        long starCount,

        @Schema(description = "내 참석 상태. 비로그인이면 null", example = "{\"status\":\"RECORDED\",\"recordId\":31}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        MyResponse my
) {

    @Schema(description = "로그인 사용자의 이 행사 참석 상태")
    public record MyResponse(
            @Schema(description = "상태", example = "GOING", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NONE", "GOING", "RECORDED"})
            String status,
            @Schema(description = "기록 ID (RECORDED 일 때). 아니면 null", example = "31", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Long recordId) {

        static MyResponse from(MySummary summary) {
            return summary == null ? null : new MyResponse(summary.status(), summary.recordId());
        }
    }

    static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Schema(description = "공연장 (프론트 Venue 계약)")
    public record VenueResponse(
            @Schema(description = "공연장 ID (PATCH /admin/venues/{id} 대상)", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
            Long id,
            @Schema(description = "공연장 이름 (원어 + 번역)", example = "{\"original\":\"KSPO DOME\",\"ko\":\"KSPO돔\"}",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            LocalizedText name,
            @Schema(description = "도시 (원어 + 번역). 없으면 null", example = "{\"original\":\"서울\",\"en\":\"Seoul\"}",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            LocalizedText city,
            @Schema(description = "국가 코드 (ISO 3166-1 alpha-2). 없으면 null", example = "KR", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String countryCode,
            @Schema(description = "좌표. 없으면 null", example = "{\"lat\":37.5199,\"lng\":127.1233}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            LocationDto location) {
    }

    @Schema(description = "외부 출처 ID (없는 제공자는 null)")
    public record SourceIds(
            @Schema(description = "네이버 캘린더 scheduleId", example = "1234567", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String naverScheduleId,
            @Schema(description = "setlist.fm setlist id", example = "63a1b2c3", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String setlistFmId,
            @Schema(description = "KOPIS 공연 ID", example = "PF178134", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String kopisId,
            @Schema(description = "Ticketmaster 이벤트 ID", example = "Z698xZC2Z17abcd", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String ticketmasterId) {
    }

    // 관리자 응답 등 참석 요약이 필요 없는 곳
    static EventResponse from(EventView view) {
        return from(view, null, 0, null);
    }

    static EventResponse from(EventView view, String playlistUrl, long starCount, MySummary my) {
        VenueResponse venue = view.venue() == null ? null
                : new VenueResponse(view.venue().id(), view.venue().name(), view.venue().city(), view.venue().countryCode(),
                        LocationDto.from(view.venue().location()));
        boolean venueLocated = view.venue() != null && view.venue().location() != null;
        return new EventResponse(view.id(), view.name(), view.type().name(),
                deriveStatus(view.status(), venueLocated, view.endsAtLocal() != null),
                view.eventDate(), time(view.startsAtLocal()), time(view.endsAtLocal()), view.timezone(), venue, view.season(),
                new SourceIds(view.externalIds().get(Provider.NAVER), view.externalIds().get(Provider.SETLISTFM),
                        view.externalIds().get(Provider.KOPIS), view.externalIds().get(Provider.TICKETMASTER)),
                view.posterUrl(), view.ticketUrl(), view.description(), view.runningMinutes(), playlistUrl, starCount,
                MyResponse.from(my));
    }

    // 취소 계열은 생명주기 그대로, 그 외는 완성도로 합성
    static String deriveStatus(EventStatus lifecycle, boolean venueLocated, boolean hasEnd) {
        if (lifecycle != EventStatus.ACTIVE) {
            return lifecycle.name();
        }
        return venueLocated && hasEnd ? "COMPLETE" : "INCOMPLETE";
    }

    static String time(LocalDateTime local) {
        return local == null ? null : local.format(HH_MM);
    }
}
