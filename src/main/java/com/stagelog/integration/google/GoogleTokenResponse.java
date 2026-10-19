package com.stagelog.integration.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 구글 토큰 응답. id_token 은 쓰지 않고 userinfo 엔드포인트로 프로필을 받는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleTokenResponse(@JsonProperty("access_token") String accessToken,
                                  @JsonProperty("token_type") String tokenType,
                                  @JsonProperty("id_token") String idToken) {
}
