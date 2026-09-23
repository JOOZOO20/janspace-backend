package com.janspace.integration.google;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GET /v1/userinfo (OpenID Connect) 응답.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoogleUserInfoResponse(@JsonProperty("sub") String sub,
                                     @JsonProperty("email") String email,
                                     @JsonProperty("name") String name,
                                     @JsonProperty("picture") String picture) {
}
