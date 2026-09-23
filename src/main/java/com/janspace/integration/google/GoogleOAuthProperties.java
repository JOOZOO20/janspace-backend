package com.janspace.integration.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 구글 로그인 설정 (janspace.oauth.google).
 */
@ConfigurationProperties(prefix = "janspace.oauth.google")
public record GoogleOAuthProperties(String clientId, String clientSecret, String authorizeUri, String tokenUri,
                                    String userinfoUri, String scope) {
}
