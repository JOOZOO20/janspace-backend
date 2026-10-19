package com.stagelog.integration.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GetScheduleList 응답의 schedules[] 항목. 비공식 API 라 필드명은 기록된 응답 기준이며 모르는 필드는 무시한다.
 * startDate/endDate 는 "yyyy-MM-dd HH:mm:ss" 또는 "yyyy-MM-dd". 종일 여부는 allDayYn("Y"/"N") 또는 allDay(boolean) 중 있는 것.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverSchedule(String scheduleId, String content, String subject, String startDate, String endDate,
                            String allDayYn, Boolean allDay) {

    public String title() {
        return content != null && !content.isBlank() ? content : subject;
    }

    public boolean isAllDay() {
        return "Y".equalsIgnoreCase(allDayYn) || Boolean.TRUE.equals(allDay);
    }
}
