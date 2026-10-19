package com.stagelog.integration.kopis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * KOPIS OpenAPI 설정 (stagelog.kopis). 서비스 키는 환경변수로만 들어온다.
 */
@ConfigurationProperties(prefix = "stagelog.kopis")
public record KopisProperties(String serviceKey, String baseUri) {
}
