package com.janspace.integration.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /v1/nid/me 응답. resultcode 가 "00" 이면 성공이고 response 에 프로필이 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverProfileResponse(@JsonProperty("resultcode") String resultCode,
                                   @JsonProperty("message") String message,
                                   @JsonProperty("response") Profile response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(@JsonProperty("id") String id,
                          @JsonProperty("email") String email,
                          @JsonProperty("nickname") String nickname,
                          @JsonProperty("profile_image") String profileImage) {
    }
}
