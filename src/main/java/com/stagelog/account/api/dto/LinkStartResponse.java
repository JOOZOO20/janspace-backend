package com.stagelog.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 계정 연결 시작 결과.
 */
@Schema(description = "제공자 인가 페이지 URL")
public record LinkStartResponse(
        @Schema(description = "브라우저를 이동시킬 제공자 인가 URL",
                example = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=...",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String authorizeUrl) {
}
