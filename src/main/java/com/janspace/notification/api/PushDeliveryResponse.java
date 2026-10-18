package com.janspace.notification.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.notification.domain.PushDelivery;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "푸시 배달 (잡 x 구독) 상태")
public record PushDeliveryResponse(
        @Schema(description = "배달 ID", example = "501", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "구독 ID", example = "77", requiredMode = Schema.RequiredMode.REQUIRED)
        Long subscriptionId,
        @Schema(description = "사용자 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long userId,
        @Schema(description = "배달 상태", example = "SENT", requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"PENDING", "SENDING", "SENT", "RETRY", "FAILED", "GONE", "CANCELLED"})
        String status,
        @Schema(description = "전송 시도 횟수 (자동 최대 3회 + 수동)", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        int attempts,
        @Schema(description = "다음 재시도 시각 (UTC). RETRY 가 아니면 null", example = "2026-10-23T14:15:00Z",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant nextAttemptAt,
        @Schema(description = "마지막 실패 사유 (500자). 없으면 null", example = "HTTP 503", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String lastError,
        @Schema(description = "전송 성공 시각 (UTC). SENT 가 아니면 null", example = "2026-10-23T14:10:03Z",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant sentAt) {

    static PushDeliveryResponse from(PushDelivery d) {
        return new PushDeliveryResponse(d.getId(), d.getSubscriptionId(), d.getUserId(), d.getStatus().name(), d.getAttempts(),
                d.getNextAttemptAt(), d.getLastError(), d.getSentAt());
    }
}
