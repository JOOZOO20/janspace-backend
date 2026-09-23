package com.janspace.notification.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "pushManager.subscribe 의 applicationServerKey 로 쓰는 VAPID 공개키")
public record VapidPublicKeyResponse(
        @Schema(description = "VAPID 공개키 (base64url, P-256 비압축 점)", example = "BEl62iUY...", requiredMode = Schema.RequiredMode.REQUIRED)
        String publicKey) {
}
