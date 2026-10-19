package com.stagelog.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 출발지. 요청(PATCH /me)과 응답(User.departure) 에 같은 형태로 쓴다.
 */
@Schema(description = "출발지 (시·군·구 또는 도시)")
public record DepartureDto(
        @Schema(description = "표시 이름", example = "서울 노원구", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "국가 코드 (ISO-3166-1 alpha-2)", example = "KR", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 2, max = 2) String countryCode,
        @Schema(description = "위도", example = "37.654", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @Schema(description = "경도", example = "127.056", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {
}
