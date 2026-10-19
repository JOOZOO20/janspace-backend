package com.stagelog.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.event.application.EventSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventOrderTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 23);

    static EventSnapshot event(long id, LocalDate date, String startsAt) {
        return new EventSnapshot(id, date, null, null, "Asia/Seoul", startsAt == null ? null : Instant.parse(startsAt), null,
                EventSnapshot.Status.ACTIVE, null, Map.of());
    }

    @Test
    @DisplayName("행사 날짜 -> 시작 순간(없으면 앞) -> ID: 같은 날 낮·밤 두 공연은 시작 순, 시각 없는 행사는 그날 맨 앞, 다른 날은 날짜가 먼저")
    void orders_by_date_then_start_then_id() {
        EventSnapshot evening = event(1, DAY, "2026-10-23T10:00:00Z");
        EventSnapshot matinee = event(2, DAY, "2026-10-23T05:00:00Z");
        EventSnapshot noTime = event(3, DAY, null);
        EventSnapshot noTimeLaterId = event(4, DAY, null);
        // 날짜가 앞이면 시작 순간이 더 늦어도 앞 (날짜가 1순위)
        EventSnapshot dayBefore = event(5, DAY.minusDays(1), "2026-10-23T12:00:00Z");

        List<EventSnapshot> sorted = List.of(evening, matinee, noTimeLaterId, noTime, dayBefore).stream()
                .sorted(EventOrder.COMPARATOR).toList();

        assertThat(sorted).containsExactly(dayBefore, noTime, noTimeLaterId, matinee, evening);
    }
}
