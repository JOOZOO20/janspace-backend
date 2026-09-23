package com.janspace.account.api.dto;

import com.janspace.account.application.port.RegionSearchPort.Place;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 출발지 검색 결과 한 건 (프론트 Place 계약).
 */
@Schema(description = "출발지 검색 결과")
public record PlaceResponse(
        @Schema(description = "표시 이름", example = "서울 노원구", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "국가 코드 (ISO-3166-1 alpha-2)", example = "KR", requiredMode = Schema.RequiredMode.REQUIRED)
        String countryCode,
        @Schema(description = "위도", example = "37.654", requiredMode = Schema.RequiredMode.REQUIRED)
        double lat,
        @Schema(description = "경도", example = "127.056", requiredMode = Schema.RequiredMode.REQUIRED)
        double lng) {

    public static PlaceResponse from(Place p) {
        return new PlaceResponse(p.name(), p.countryCode(), p.lat(), p.lng());
    }
}
