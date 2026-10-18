package com.janspace.integration.naver;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 네이버 공개 캘린더 내부 API 설정 (janspace.naver-calendar). 기본값은 설계 의 공개 키·캘린더 ID.
 */
@ConfigurationProperties(prefix = "janspace.naver-calendar")
public record NaverCalendarProperties(String publishedKey, String calendarId, String scheduleListUri, String refererUri) {
}
