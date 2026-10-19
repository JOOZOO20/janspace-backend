package com.stagelog.integration.naver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 네이버 로그인 설정 (stagelog.oauth.naver). 클라이언트 ID·시크릿은 환경변수로만 들어온다.
 */
@ConfigurationProperties(prefix = "stagelog.oauth.naver")
public record NaverOAuthProperties(String clientId, String clientSecret, String authorizeUri, String tokenUri,
                                   String userinfoUri) {
}
