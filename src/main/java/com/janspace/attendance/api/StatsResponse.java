package com.janspace.attendance.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.attendance.application.StatsQuery.Stats;
import com.janspace.attendance.domain.AltitudeScale.Headline;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 프론트 Stats 계약 + 높이 헤드라인(altitude, 추가 필드).
 */
@Schema(description = "내 통계")
public record StatsResponse(
        @Schema(description = "누적 거리(km). 좌표 없는 기록은 제외", example = "966.0", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal totalKm,
        @Schema(description = "기록(별) 수", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
        long recordCount,
        @Schema(description = "마지막 기록 시각 (UTC). 없으면 null", example = "2026-10-23T12:30:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant lastRecordAt,
        @Schema(description = "높이 헤드라인", example = "{\"passedKm\":600,\"nextKm\":2500,\"remainingKm\":1534,\"nextNear\":false}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Altitude altitude) {

    @Schema(description = "높이 헤드라인: 마지막으로 지난 기준과 다음 기준")
    public record Altitude(
            @Schema(description = "마지막으로 지난 기준(km). 10km 미만이면 null", example = "600", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer passedKm,
            @Schema(description = "다음 기준(km). 달(10000)을 넘었으면 null", example = "2500", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer nextKm,
            @Schema(description = "다음 기준까지 남은 거리(km). nextKm 이 null 이면 null", example = "1534", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer remainingKm,
            @Schema(description = "다음 기준이 가까운가 (남은 거리 <= 다음 기준 x 0.25). 프론트는 가까울 때만 작게 보여준다", example = "false",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean nextNear) {

        static Altitude from(Headline h) {
            return new Altitude(h.passedKm(), h.nextKm(), h.remainingKm(), h.nextNear());
        }
    }

    static StatsResponse from(Stats stats) {
        return new StatsResponse(stats.totalKm(), stats.recordCount(), stats.lastRecordAt(), Altitude.from(stats.altitude()));
    }
}
