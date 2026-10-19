package com.stagelog.event.infrastructure;

import com.stagelog.event.application.port.NaverCalendarPort;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.integration.naver.NaverCalendarClient;
import com.stagelog.integration.naver.NaverSchedule;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 네이버 캘린더 일정 -> 날짜별 SourceSnapshot. 종일 일정이라 시각·공연장은 없다.
 * 여러 날 일정(예: 10/23~25)은 날짜마다 하나. 종료가 다음 날 00:00:00 이면 배타 종료로 보고 하루를 뺀다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NaverSourceAdapter implements NaverCalendarPort {

    private final NaverCalendarClient client;

    @Override
    public boolean isEnabled() {
        return client.isEnabled();
    }

    @Override
    public List<SourceSnapshot> occurrences(LocalDate from, LocalDate to) {
        List<SourceSnapshot> out = new ArrayList<>();
        for (NaverSchedule schedule : client.schedules(from, to)) {
            LocalDate start = date(schedule.startDate());
            LocalDate end = endDate(schedule.endDate(), start);
            if (schedule.scheduleId() == null || start == null) {
                log.warn("[네이버 일정 무시] scheduleId 또는 startDate 없음. scheduleId={}", schedule.scheduleId());
                continue;
            }
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                if (date.isBefore(from) || date.isAfter(to)) {
                    continue;
                }
                out.add(new SourceSnapshot(schedule.scheduleId(), date.toString(), schedule.title(), null, null, null, null,
                        null, date, null));
            }
        }
        return out;
    }

    private static LocalDate date(String text) {
        if (text == null || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate endDate(String text, LocalDate start) {
        LocalDate end = date(text);
        if (end == null || start == null || end.isBefore(start)) {
            return start;
        }
        boolean midnight = text.length() >= 19 && text.substring(11, 19).equals("00:00:00");
        return midnight && end.isAfter(start) ? end.minusDays(1) : end;
    }
}
