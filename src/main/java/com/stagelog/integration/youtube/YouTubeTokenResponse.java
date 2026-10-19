package com.stagelog.integration.youtube;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 구글 OAuth 토큰 응답. refreshToken 은 authorization_code 교환(access_type=offline, prompt=consent) 때만 온다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record YouTubeTokenResponse(@JsonProperty("access_token") String accessToken,
                                   @JsonProperty("refresh_token") String refreshToken,
                                   @JsonProperty("expires_in") Integer expiresIn) {
}
