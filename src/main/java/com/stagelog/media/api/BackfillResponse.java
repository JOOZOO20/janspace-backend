package com.stagelog.media.api;

import com.stagelog.media.application.MediaBackfill;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "백필 진행 상태")
public record BackfillResponse(
        @Schema(description = "원본 소유자 종류. 한 번도 실행하지 않았으면 null", example = "EVENT_POSTER", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String ownerType,
        @Schema(description = "요청한 최대 등록 수", example = "10000", requiredMode = Schema.RequiredMode.REQUIRED)
        int limit,
        @Schema(description = "실행 중 여부", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean running,
        @Schema(description = "마지막으로 본 소유자 ID. 다시 시작하면 이미 등록된 소유자는 건너뛰므로 여기서 이어진다", example = "4210",
                requiredMode = Schema.RequiredMode.REQUIRED)
        long cursor,
        @Schema(description = "이번 실행에서 등록·발행한 원본 수", example = "4200", requiredMode = Schema.RequiredMode.REQUIRED)
        int registered,
        @Schema(description = "이미 등록돼 건너뛴 원본 수", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
        int skipped,
        @Schema(description = "S3 에 원본이 없어 건너뛴 수", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int missing,
        @Schema(description = "시작 시각 (UTC)", example = "2026-11-05T12:00:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant startedAt,
        @Schema(description = "종료 시각 (UTC). 실행 중이면 null", example = "2026-11-05T12:08:20Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant finishedAt) {

    static BackfillResponse from(MediaBackfill.Status s) {
        return new BackfillResponse(s.ownerType() == null ? null : s.ownerType().name(), s.limit(), s.running(), s.cursor(), s.registered(),
                s.skipped(), s.missing(), s.startedAt(), s.finishedAt());
    }
}
