package com.janspace.event.api;

import com.janspace.event.domain.GeoPoint;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 위경도. 프론트 {lat, lng}.
 */
@Schema(description = "위경도 좌표")
public record LocationDto(
        @Schema(description = "위도", example = "37.520", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Double lat,
        @Schema(description = "경도", example = "127.126", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Double lng) {

    static LocationDto from(GeoPoint point) {
        return point == null ? null : new LocationDto(point.lat(), point.lng());
    }

    GeoPoint toPoint() {
        return new GeoPoint(lat, lng);
    }
}
