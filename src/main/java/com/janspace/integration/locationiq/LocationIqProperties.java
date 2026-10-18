package com.janspace.integration.locationiq;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LocationIQ 검색 설정 (janspace.locationiq).
 */
@ConfigurationProperties(prefix = "janspace.locationiq")
public record LocationIqProperties(String token, String searchUri) {
}
