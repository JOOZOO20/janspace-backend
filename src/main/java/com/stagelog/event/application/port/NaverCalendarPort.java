package com.stagelog.event.application.port;

import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import java.time.LocalDate;
import java.util.List;

/**
 * 네이버 공개 캘린더 회차 조회. 여러 날 일정은 날짜마다 스냅샷 하나(externalId=scheduleId, occurrenceKey=YYYY-MM-DD)로 펼쳐 준다.
 */
public interface NaverCalendarPort {

    boolean isEnabled();

    List<SourceSnapshot> occurrences(LocalDate from, LocalDate to);
}
