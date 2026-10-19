package com.stagelog.event.infrastructure;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KOPIS dtguidance 파서 (순수). 예: "화요일 ~ 금요일(20:00), 토요일(16:00,19:00), 일요일(15:00,18:00)".
 * 요일 그룹 하나 = "요일들(시각들)". 요일은 단일 또는 "~" 범위, 시각은 쉼표 구분 HH:mm. 모르는 토큰(HOL, 공휴일 등)은 시각 없음으로 본다.
 */
public final class DtGuidanceParser {

    private static final Pattern GROUP = Pattern.compile("([^()]+)\\(([^)]*)\\)");
    private static final Pattern TIME = Pattern.compile("\\d{1,2}:\\d{2}");
    private static final Map<String, DayOfWeek> DAYS = Map.of(
            "월", DayOfWeek.MONDAY, "화", DayOfWeek.TUESDAY, "수", DayOfWeek.WEDNESDAY, "목", DayOfWeek.THURSDAY,
            "금", DayOfWeek.FRIDAY, "토", DayOfWeek.SATURDAY, "일", DayOfWeek.SUNDAY);

    private DtGuidanceParser() {
    }

    // 요일 -> 시각 목록 (선언 순서 유지). 파싱 불가 문자열은 빈 맵
    public static Map<DayOfWeek, List<LocalTime>> parse(String dtguidance) {
        Map<DayOfWeek, List<LocalTime>> result = new EnumMap<>(DayOfWeek.class);
        if (dtguidance == null || dtguidance.isBlank()) {
            return result;
        }
        Matcher m = GROUP.matcher(dtguidance);
        while (m.find()) {
            List<LocalTime> times = times(m.group(2));
            for (DayOfWeek day : days(m.group(1))) {
                result.computeIfAbsent(day, d -> new ArrayList<>()).addAll(times);
            }
        }
        return result;
    }

    // 그 날짜 요일의 회차 시각들. 없으면 빈 목록
    public static List<LocalTime> sessionsOn(String dtguidance, LocalDate date) {
        return parse(dtguidance).getOrDefault(date.getDayOfWeek(), List.of());
    }

    // "화요일 ~ 금요일" -> 화..금, "토요일" -> 토, 그 외 -> 없음
    private static List<DayOfWeek> days(String text) {
        String[] parts = text.split(",");
        List<DayOfWeek> days = new ArrayList<>();
        for (String part : parts) {
            String[] range = part.split("~");
            DayOfWeek start = day(range[0]);
            DayOfWeek end = range.length > 1 ? day(range[1]) : start;
            if (start == null || end == null) {
                continue;
            }
            DayOfWeek d = start;
            while (true) {
                days.add(d);
                if (d == end) {
                    break;
                }
                d = d.plus(1);
            }
        }
        return days;
    }

    private static DayOfWeek day(String token) {
        String t = token.trim();
        return t.isEmpty() ? null : DAYS.get(t.substring(0, 1));
    }

    private static List<LocalTime> times(String text) {
        List<LocalTime> times = new ArrayList<>();
        Matcher m = TIME.matcher(text);
        while (m.find()) {
            try {
                String[] hm = m.group().split(":");
                times.add(LocalTime.of(Integer.parseInt(hm[0]), Integer.parseInt(hm[1])));
            } catch (NumberFormatException | java.time.DateTimeException ignored) {
                // 잘못된 시각 토큰은 건너뛴다
            }
        }
        return times;
    }
}
