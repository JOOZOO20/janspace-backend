package com.stagelog.integration.locationiq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LocationIQ 검색 설정 (stagelog.locationiq).
 */
@ConfigurationProperties(prefix = "stagelog.locationiq")
public record LocationIqProperties(String token, String searchUri) {
}
