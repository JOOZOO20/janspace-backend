package com.janspace.integration.setlistfm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * setlist.fm API 설정 (janspace.setlistfm). 키와 잔나비 MBID 는 환경변수로만 들어온다.
 */
@ConfigurationProperties(prefix = "janspace.setlistfm")
public record SetlistFmProperties(String apiKey, String artistMbid, String baseUri) {
}
