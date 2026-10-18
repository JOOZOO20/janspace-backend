package com.janspace.event.api;

import com.janspace.event.domain.GeoPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 위경도. 프론트 {lat, lng}.
 */
@Schema(description = "위경도 좌표")
public record LocationDto(
        @Schema(description = "위도 (-90~90)", example = "37.520", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
        @Schema(description = "경도 (-180~180)", example = "127.126", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng) {

    static LocationDto from(GeoPoint point) {
        return point == null ? null : new LocationDto(point.lat(), point.lng());
    }

    GeoPoint toPoint() {
        return new GeoPoint(lat, lng);
    }
}
