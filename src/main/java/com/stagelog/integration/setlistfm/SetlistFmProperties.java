package com.stagelog.integration.setlistfm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * setlist.fm API 설정 (stagelog.setlistfm). 키와 아티스트 MBID 는 환경변수로만 들어온다.
 */
@ConfigurationProperties(prefix = "stagelog.setlistfm")
public record SetlistFmProperties(String apiKey, String artistMbid, String baseUri) {
}
