package com.janspace.integration.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 네이버 토큰 응답. 실패 시 error·error_description 이 온다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverTokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("token_type") String tokenType,
                                 @JsonProperty("error") String error,
                                 @JsonProperty("error_description") String errorDescription) {
}
