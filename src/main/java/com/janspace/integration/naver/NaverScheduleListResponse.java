package com.janspace.integration.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * GetScheduleList 응답 봉투. schedules 외 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverScheduleListResponse(List<NaverSchedule> schedules) {
}
