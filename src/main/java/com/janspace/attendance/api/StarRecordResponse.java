package com.janspace.attendance.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.attendance.application.AttendanceQuery.Star;
import com.janspace.attendance.domain.Attendance;
import com.janspace.attendance.domain.Emotion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 프론트 StarRecord 계약. 별 밝기는 emotions 유무 + photocardId 유무로 프론트가 계산한다 (DIM / BRIGHT / BRIGHTEST).
 */
@Schema(description = "기록 (별 하나)")
public record StarRecordResponse(
        @Schema(description = "기록 ID", example = "31", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long eventId,
        @Schema(description = "감정 코드, 순서 = 순위. 소급이면 빈 배열", example = "[\"HAPPY\", \"OVERWHELMED\"]", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Emotion> emotions,
        @Schema(description = "최고의 곡 ID. 없으면 null", example = "5", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long songId,
        @Schema(description = "한 줄 메모. 없으면 null", example = "앙코르에서 울었다", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String memo,
        @Schema(description = "소급 기록 여부 (감정 없음 = 흐린 별)", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isRetro,
        @Schema(description = "연속 이동 여부", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean chainedFromPrevious,
        @Schema(description = "기록 시점 출발지 스냅샷. 없으면 null", example = "{\"name\":\"서울 노원구\",\"countryCode\":\"KR\",\"lat\":37.654,\"lng\":127.056}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        PlaceDto departure,
        @Schema(description = "이 기록의 거리(km). 공연장 좌표가 없으면 null", example = "27.8", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        BigDecimal distanceKm,
        @Schema(description = "이 기록 시점의 누적 거리(km, 행사 순) = 별 높이", example = "966.0", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal cumulativeKm,
        @Schema(description = "연결된 포토카드 ID. 없으면 null (있으면 가장 밝은 별)", example = "15", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long photocardId,
        @Schema(description = "생성 시각 (UTC)", example = "2026-10-23T12:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt,
        @Schema(description = "수정 시각 (UTC)", example = "2026-10-23T12:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant updatedAt) {

    static StarRecordResponse from(Star star) {
        Attendance a = star.attendance();
        return new StarRecordResponse(a.getId(), a.getEventId(), a.emotions(), a.getBestSongId(), a.getMemo(), a.isRetro(),
                a.isContinuedFromPrevious(), PlaceDto.from(a.getDeparture()), a.getDistanceKm(), star.cumulativeKm(), star.photocardId(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
