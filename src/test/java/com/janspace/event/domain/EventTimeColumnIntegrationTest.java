package com.janspace.event.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.event.support.EventIntegrationTest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 벽시계 컬럼(starts_at_local)과 UTC 순간 컬럼(starts_at)의 DB 원본 값. 앱으로 다시 읽으면 변환이 되돌려져 보이지 않으므로 SQL 로 직접 읽는다.
 */
class EventTimeColumnIntegrationTest extends EventIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @ParameterizedTest(name = "JVM 기본 시간대 {0}")
    @ValueSource(strings = {"Asia/Seoul", "UTC"})
    @DisplayName("서울 19:30 공연은 JVM 기본 시간대와 무관하게 starts_at_local 19:30, starts_at 10:30Z 로 저장되고 그대로 읽힌다")
    void wall_clock_is_stored_as_is_regardless_of_jvm_zone(String jvmZone) {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
        try {
            // 1. 저장 후 DB 원본 값
            Long id = newEventWithStart("KSPO 공연", DATE, DATE.atTime(19, 30)).getId();
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT CAST(event_date AS CHAR) AS date_at, CAST(starts_at_local AS CHAR) AS local_at, CAST(starts_at AS CHAR) AS utc_at"
                            + " FROM events WHERE id = ?", id);
            assertThat(row.get("date_at")).isEqualTo("2026-10-23");
            assertThat(row.get("local_at")).isEqualTo("2026-10-23 19:30:00");
            assertThat(row.get("utc_at")).isEqualTo("2026-10-23 10:30:00.000000");

            // 2. SQL 로 넣은 벽시계 값은 앱에서 그대로 읽힌다
            jdbc.update("UPDATE events SET starts_at_local = '2026-10-23 20:00:00' WHERE id = ?", id);
            assertThat(eventRepository.findById(id).orElseThrow().getStartsAtLocal())
                    .isEqualTo(LocalDateTime.of(2026, 10, 23, 20, 0));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
