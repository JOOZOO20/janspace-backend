package com.stagelog.notification.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 브라우저 PushSubscription.toJSON() 그대로. expirationTime 은 받기만 하고 저장하지 않는다 (브라우저가 갱신 시 재구독).
 */
@Schema(description = "웹 푸시 구독 등록 요청 (PushSubscriptionJSON)")
public record PushSubscriptionRequest(
        @Schema(description = "푸시 서비스 endpoint URL", example = "https://fcm.googleapis.com/fcm/send/abc...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String endpoint,
        @Schema(description = "구독 만료 시각(epoch ms). 브라우저가 주면 그대로, 보통 null", example = "null",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long expirationTime,
        @Schema(description = "암호화 키", example = "{\"p256dh\":\"BNcRd...\",\"auth\":\"tBHItJI5...\"}", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Valid Keys keys) {

    @Schema(description = "구독 암호화 키 (base64url)")
    public record Keys(
            @Schema(description = "클라이언트 P-256 공개키", example = "BNcRd...", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String p256dh,
            @Schema(description = "인증 비밀", example = "tBHItJI5...", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String auth) {
    }
}
