package com.stagelog.integration.locationiq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * /v1/search 응답 배열의 원소 (format=json, addressdetails=1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LocationIqPlace(@JsonProperty("lat") String lat,
                              @JsonProperty("lon") String lon,
                              @JsonProperty("display_name") String displayName,
                              @JsonProperty("address") Address address) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(@JsonProperty("city") String city,
                          @JsonProperty("town") String town,
                          @JsonProperty("village") String village,
                          @JsonProperty("state") String state,
                          @JsonProperty("country") String country,
                          @JsonProperty("country_code") String countryCode) {
    }
}
