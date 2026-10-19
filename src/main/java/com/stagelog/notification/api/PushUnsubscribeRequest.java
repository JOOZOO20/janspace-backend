package com.stagelog.notification.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 구독 해지 요청. 브라우저가 아는 식별자는 PushSubscription.endpoint 뿐이라 endpoint 로 찾는다.
 * endpoint 는 URL 에 싣지 않는다 (프록시·접근 로그에 남지 않게 본문으로 받는다).
 */
@Schema(description = "웹 푸시 구독 해지 요청")
public record PushUnsubscribeRequest(
        @Schema(description = "해지할 구독의 푸시 서비스 endpoint URL (PushSubscription.endpoint)", example = "https://fcm.googleapis.com/fcm/send/abc...",
                maxLength = 512, requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 512) String endpoint) {
}
