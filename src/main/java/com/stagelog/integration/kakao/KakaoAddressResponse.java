package com.stagelog.integration.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * GET /v2/local/search/address.json 응답. x 가 경도, y 가 위도다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoAddressResponse(@JsonProperty("documents") List<Document> documents) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(@JsonProperty("address_name") String addressName,
                           @JsonProperty("address") Address address,
                           @JsonProperty("x") String x,
                           @JsonProperty("y") String y) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(@JsonProperty("region_1depth_name") String region1,
                          @JsonProperty("region_2depth_name") String region2,
                          @JsonProperty("region_3depth_name") String region3) {
    }
}
