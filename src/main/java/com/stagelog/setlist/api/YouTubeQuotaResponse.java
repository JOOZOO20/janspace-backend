package com.stagelog.setlist.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.setlist.application.QuotaUsage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "하루(태평양 날짜) 유튜브 Quota 사용 현황")
public record YouTubeQuotaResponse(
        @Schema(description = "Quota 날짜 (태평양 시간 기준, 리셋은 태평양 자정)", example = "2026-11-05", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate date,
        @Schema(description = "bucket 별 한도·사용·예약. 일일 행이 아직 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
        List<BucketResponse> buckets,
        @Schema(description = "행사별 사용량 상위 10개 (CONSUMED + UNKNOWN)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<EventUsageResponse> topEvents) {

    @Schema(description = "bucket 하나의 Quota")
    public record BucketResponse(
            @Schema(description = "예산 구분", example = "AUTO", allowableValues = {"AUTO", "MANUAL"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String bucket,
            @Schema(description = "하루 한도 (unit)", example = "8000", requiredMode = Schema.RequiredMode.REQUIRED)
            int limitUnits,
            @Schema(description = "사용 (응답 받음 + 결과 모름, unit)", example = "1250", requiredMode = Schema.RequiredMode.REQUIRED)
            int usedUnits,
            @Schema(description = "예약했지만 아직 결과가 없는 몫 (unit)", example = "100", requiredMode = Schema.RequiredMode.REQUIRED)
            int reservedUnits,
            @Schema(description = "quotaExceeded 를 받은 시각 (UTC). 있으면 그날 동기화가 멈춘다", example = "2026-11-05T17:30:00Z",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant exhaustedAt) {
    }

    @Schema(description = "행사 하나의 그날 사용량")
    public record EventUsageResponse(
            @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
            Long eventId,
            @Schema(description = "사용 (unit)", example = "152", requiredMode = Schema.RequiredMode.REQUIRED)
            long units) {
    }

    public static YouTubeQuotaResponse from(QuotaUsage usage) {
        return new YouTubeQuotaResponse(usage.date(),
                usage.buckets().stream().map(b -> new BucketResponse(b.bucket(), b.limitUnits(), b.usedUnits(), b.reservedUnits(), b.exhaustedAt())).toList(),
                usage.topEvents().stream().map(e -> new EventUsageResponse(e.eventId(), e.units())).toList());
    }
}
