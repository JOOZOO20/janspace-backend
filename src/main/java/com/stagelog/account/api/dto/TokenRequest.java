package com.stagelog.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 일회용 코드 교환 요청.
 */
@Schema(description = "일회용 인증 코드 + PKCE verifier 교환 요청")
public record TokenRequest(
        @Schema(description = "콜백 리다이렉트로 받은 일회용 코드", example = "Qm9ndXNDb2RlLTMyYnl0ZXMtYmFzZTY0dXJs",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String code,
        @Schema(description = "로그인 시작 때 만든 PKCE code_verifier (43~128자)",
                example = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 43, max = 128) String codeVerifier) {
}
