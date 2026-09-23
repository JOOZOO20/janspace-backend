package com.janspace.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 계정 연결 시작 요청.
 */
@Schema(description = "계정 연결 시작 요청 (PKCE)")
public record LinkStartRequest(
        @Schema(description = "PKCE code_challenge (S256, base64url)", example = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank String codeChallenge) {
}
