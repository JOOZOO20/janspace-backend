package com.janspace.integration.kakao;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 카카오 로컬 키워드 검색 documents 항목. x = 경도, y = 위도 (문자열).
 */
public record KakaoPlace(
        @JsonProperty("place_name") String placeName,
        @JsonProperty("address_name") String addressName,
        @JsonProperty("x") String x,
        @JsonProperty("y") String y) {
}
