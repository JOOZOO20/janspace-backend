package com.stagelog.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 액세스 토큰과 사용자. 리프레시 토큰은 HttpOnly 쿠키(refresh_token)로만 내려간다.
 */
@Schema(description = "토큰 발급 결과. 리프레시 토큰은 Set-Cookie 로 전달")
public record TokenResponse(
        @Schema(description = "액세스 JWT (30분). Authorization: Bearer 로 전달", example = "eyJhbGciOiJIUzI1NiJ9...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String accessToken,
        @Schema(description = "로그인한 사용자 (GET /me 와 같은 형태)",
                example = "{\"id\":1024,\"nickname\":\"잔나비 팬\",\"email\":\"fan@example.com\",\"providers\":[\"NAVER\"],"
                        + "\"isAdmin\":false,\"consentedAt\":null,\"departure\":null,\"lang\":\"ko\",\"avatarUrl\":null,"
                        + "\"createdAt\":\"2026-09-24T09:00:00Z\"}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UserResponse user) {
}
