package com.stagelog.integration.naver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 네이버 공개 캘린더 내부 API 설정 (stagelog.naver-calendar). publishedKey 가 비어 있으면 동기화를 건너뛴다.
 */
@ConfigurationProperties(prefix = "stagelog.naver-calendar")
public record NaverCalendarProperties(String publishedKey, String scheduleListUri, String refererUri) {
}
