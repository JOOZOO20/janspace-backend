package com.stagelog.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 약관 동의 요청.
 */
@Schema(description = "약관 동의 요청")
public record ConsentRequest(
        @Schema(description = "동의한 약관 버전", example = "2026-09-01", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 20) String termsVersion) {
}
