package com.stagelog.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 계정 연결 완료 요청.
 */
@Schema(description = "연결 코드 + PKCE verifier 로 계정 연결을 완료")
public record LinkCompleteRequest(
        @Schema(description = "link-callback 리다이렉트로 받은 일회용 연결 코드", example = "Qm9ndXNDb2RlLTMyYnl0ZXMtYmFzZTY0dXJs",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String code,
        @Schema(description = "연결 시작 때 만든 PKCE code_verifier", example = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 43, max = 128) String codeVerifier) {
}
