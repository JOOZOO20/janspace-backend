package com.janspace.attendance.api;

import com.janspace.attendance.application.RecordInput;
import com.janspace.attendance.domain.DepartureSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 프론트 Place 계약. 기록의 출발지 스냅샷(요청·응답 공용).
 */
@Schema(description = "출발지 (시·군·구 또는 도시)")
public record PlaceDto(
        @Schema(description = "표시 이름", example = "서울 노원구", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String name,
        @Schema(description = "국가 코드 (ISO-3166-1 alpha-2)", example = "KR", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(min = 2, max = 2) String countryCode,
        @Schema(description = "위도", example = "37.654", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @Schema(description = "경도", example = "127.056", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {

    static PlaceDto from(DepartureSnapshot snapshot) {
        if (snapshot == null || snapshot.getLat() == null || snapshot.getLng() == null) {
            return null;
        }
        return new PlaceDto(snapshot.getName(), snapshot.getCountryCode(), snapshot.getLat().doubleValue(), snapshot.getLng().doubleValue());
    }

    RecordInput.Departure toInput() {
        return new RecordInput.Departure(name, countryCode, lat, lng);
    }
}
