package com.janspace.event.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DtGuidanceParserTest {

    @Test
    @DisplayName("요일 범위·복수 시각: 화~금 20:00, 토 16:00·19:00, 일 15:00·18:00")
    void parses_ranges_and_multiple_times() {
        Map<DayOfWeek, List<LocalTime>> parsed = DtGuidanceParser.parse("화요일 ~ 금요일(20:00), 토요일(16:00,19:00), 일요일(15:00,18:00)");

        assertThat(parsed).containsOnlyKeys(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(parsed.get(DayOfWeek.WEDNESDAY)).containsExactly(LocalTime.of(20, 0));
        assertThat(parsed.get(DayOfWeek.SATURDAY)).containsExactly(LocalTime.of(16, 0), LocalTime.of(19, 0));
        assertThat(parsed.get(DayOfWeek.SUNDAY)).containsExactly(LocalTime.of(15, 0), LocalTime.of(18, 0));
        // 2026-10-23 금요일 / 10-24 토요일 / 10-26 월요일(없음)
        assertThat(DtGuidanceParser.sessionsOn("화요일 ~ 금요일(20:00), 토요일(16:00,19:00)", LocalDate.of(2026, 10, 23)))
                .containsExactly(LocalTime.of(20, 0));
        assertThat(DtGuidanceParser.sessionsOn("화요일 ~ 금요일(20:00), 토요일(16:00,19:00)", LocalDate.of(2026, 10, 24)))
                .hasSize(2);
        assertThat(DtGuidanceParser.sessionsOn("화요일 ~ 금요일(20:00), 토요일(16:00,19:00)", LocalDate.of(2026, 10, 26))).isEmpty();
    }

    @Test
    @DisplayName("단일 요일 토요일(18:00), 요일 축약 '토(18:00)', 주 경계를 넘는 범위 '금요일 ~ 일요일'")
    void single_day_short_name_and_wrapping_range() {
        assertThat(DtGuidanceParser.parse("토요일(18:00)")).containsExactly(Map.entry(DayOfWeek.SATURDAY, List.of(LocalTime.of(18, 0))));
        assertThat(DtGuidanceParser.parse("토(18:00)")).containsKey(DayOfWeek.SATURDAY);
        assertThat(DtGuidanceParser.parse("금요일 ~ 일요일(19:00)")).containsOnlyKeys(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
    }

    @Test
    @DisplayName("모르는 토큰(HOL·공휴일)은 시각 없음, 빈 문자열·null·괄호 없는 문자열은 빈 결과")
    void unknown_tokens_are_ignored() {
        assertThat(DtGuidanceParser.parse("HOL(19:00)")).isEmpty();
        assertThat(DtGuidanceParser.parse("토요일(18:00), 공휴일(15:00)")).containsOnlyKeys(DayOfWeek.SATURDAY);
        assertThat(DtGuidanceParser.parse("")).isEmpty();
        assertThat(DtGuidanceParser.parse(null)).isEmpty();
        assertThat(DtGuidanceParser.parse("공연 시간 미정")).isEmpty();
        assertThat(DtGuidanceParser.parse("토요일(미정)")).containsEntry(DayOfWeek.SATURDAY, List.of());
    }
}
