package com.janspace.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PushJobPlannerTest {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    // 기본 설정값: 공식 공연 시간 +10분, 추정 +20분, 날짜만 있으면 당일 22:00
    static final PushJobPlanner PLANNER = new PushJobPlanner(Duration.ofMinutes(10), Duration.ofMinutes(20), LocalTime.of(22, 0));

    static Instant at(int hour, int minute, ZoneId zone) {
        return DATE.atTime(hour, minute).atZone(zone).toInstant();
    }

    @Test
    @DisplayName("P1 방콕 21:00 종료 -> RECORD 14:10Z. 시작·공연 시간이 있어도 종료 시각이 이긴다")
    void p1_record_ten_minutes_after_end() {
        PushJobPlanner.Plan plan = PLANNER.plan(at(21, 0, BANGKOK), at(19, 0, BANGKOK), Duration.ofMinutes(140), true, DATE,
                "Asia/Bangkok");
        assertThat(plan.kind()).isEqualTo(PushKind.RECORD);
        assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-10-23T14:10:00Z"));
    }

    @Test
    @DisplayName("P2 서울 19:00 시작 + 공식 140분 + 10분 -> RECORD 21:30 KST = 12:30Z")
    void p2_official_running_time_plus_official_buffer() {
        PushJobPlanner.Plan plan = PLANNER.plan(null, at(19, 0, SEOUL), Duration.ofMinutes(140), true, DATE, "Asia/Seoul");
        assertThat(plan.kind()).isEqualTo(PushKind.RECORD);
        assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-10-23T12:30:00Z"));
    }

    @Test
    @DisplayName("P2 방콕 20:00 시작 + 추정 150분 + 20분 -> RECORD 22:50 방콕 = 15:50Z")
    void p2_estimated_running_time_plus_estimated_buffer() {
        PushJobPlanner.Plan plan = PLANNER.plan(null, at(20, 0, BANGKOK), Duration.ofMinutes(150), false, DATE, "Asia/Bangkok");
        assertThat(plan.kind()).isEqualTo(PushKind.RECORD);
        assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-10-23T15:50:00Z"));
    }

    @Test
    @DisplayName("P2 자정 넘김: 서울 22:30 시작 + 공식 140분 + 10분 -> 다음 날 01:00 KST 의 RECORD")
    void p2_crosses_midnight() {
        PushJobPlanner.Plan plan = PLANNER.plan(null, at(22, 30, SEOUL), Duration.ofMinutes(140), true, DATE, "Asia/Seoul");
        assertThat(plan.kind()).isEqualTo(PushKind.RECORD);
        assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-10-23T16:00:00Z"));
        assertThat(LocalDateTime.ofInstant(plan.dueAt(), SEOUL)).isEqualTo(DATE.plusDays(1).atTime(1, 0));
    }

    @Test
    @DisplayName("P3 날짜만: 행사 당일 공연장 현지 22:00 RECORD_FALLBACK (서울 13:00Z, 방콕 15:00Z)")
    void p3_date_only_same_day_local_evening() {
        PushJobPlanner.Plan seoul = PLANNER.plan(null, null, null, false, DATE, "Asia/Seoul");
        assertThat(seoul.kind()).isEqualTo(PushKind.RECORD_FALLBACK);
        assertThat(seoul.dueAt()).isEqualTo(Instant.parse("2026-10-23T13:00:00Z"));

        PushJobPlanner.Plan bangkok = PLANNER.plan(null, null, null, false, DATE, "Asia/Bangkok");
        assertThat(bangkok.kind()).isEqualTo(PushKind.RECORD_FALLBACK);
        assertThat(bangkok.dueAt()).isEqualTo(Instant.parse("2026-10-23T15:00:00Z"));
    }
}
