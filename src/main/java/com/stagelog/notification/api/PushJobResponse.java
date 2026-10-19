package com.stagelog.notification.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.notification.domain.PushDelivery;
import com.stagelog.notification.domain.PushJob;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "푸시 잡. 상세 조회에서만 deliveries 가 채워진다")
public record PushJobResponse(
        @Schema(description = "잡 ID", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long eventId,
        @Schema(description = "잡 종류 (DB 값)", example = "RECORD", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"RECORD", "RECORD_FALLBACK", "SETLIST_CONFIRMED"})
        String kind,
        @Schema(description = "발송 예정 시각 (UTC)", example = "2026-10-23T14:10:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant dueAt,
        @Schema(description = "잡 상태", example = "DONE", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"SCHEDULED", "DISPATCHING", "DONE", "CANCELLED"})
        String status,
        @Schema(description = "선점 시각 (UTC). 없으면 null", example = "2026-10-23T14:10:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant claimedAt,
        @Schema(description = "배달 집합 확정 시각 (UTC). 없으면 null", example = "2026-10-23T14:10:00Z",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant materializedAt,
        @Schema(description = "배달 목록. 목록 조회에서는 null",
                example = "[{\"id\":501,\"subscriptionId\":7,\"userId\":3,\"status\":\"SENT\",\"attempts\":1,"
                        + "\"nextAttemptAt\":null,\"lastError\":null,\"sentAt\":\"2026-10-23T14:10:05Z\"}]",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<PushDeliveryResponse> deliveries) {

    static PushJobResponse from(PushJob job, List<PushDelivery> deliveries) {
        return new PushJobResponse(job.getId(), job.getEventId(), job.getKind().name(), job.getDueAt(), job.getStatus().name(),
                job.getClaimedAt(), job.getMaterializedAt(),
                deliveries == null ? null : deliveries.stream().map(PushDeliveryResponse::from).toList());
    }
}
