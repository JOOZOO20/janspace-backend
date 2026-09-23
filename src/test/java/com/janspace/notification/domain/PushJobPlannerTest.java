package com.janspace.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PushJobPlannerTest {

    @Test
    @DisplayName("P1 방콕 2026-10-23 21:00 종료 -> RECORD, 14:10Z")
    void p1_record_ten_minutes_after_end() {
        Instant endsAt = LocalDateTime.of(2026, 10, 23, 21, 0).atZone(ZoneId.of("Asia/Bangkok")).toInstant();
        PushJobPlanner.Plan plan = PushJobPlanner.plan(endsAt, LocalDate.of(2026, 10, 23), "Asia/Bangkok");
        assertThat(plan.kind()).isEqualTo(PushKind.RECORD);
        assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-10-23T14:10:00Z"));
    }

    @Test
    @DisplayName("P2 종료 시각 없음, 타이베이 -> RECORD_FALLBACK, 다음 날 현지 10:00 = 02:00Z")
    void p2_fallback_next_day_ten_local() {
        PushJobPlanner.Plan plan = PushJobPlanner.plan(null, LocalDate.of(2026, 10, 23), "Asia/Taipei");
        assertThat(plan.kind()).isEqualTo(PushKind.RECORD_FALLBACK);
        assertThat(plan.dueAt()).isEqualTo(Instant.parse("2026-10-24T02:00:00Z"));
    }
}
