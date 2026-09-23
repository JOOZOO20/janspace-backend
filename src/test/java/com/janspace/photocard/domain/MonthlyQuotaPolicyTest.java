package com.janspace.photocard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthlyQuotaPolicyTest {

    @Test
    @DisplayName("월 창은 UTC 기준 [이달 1일 00:00, 다음 달 1일 00:00) 이다. 월말 23:59:59 도 같은 달")
    void month_window_utc() {
        MonthlyQuotaPolicy.Window w = MonthlyQuotaPolicy.monthOf(Instant.parse("2026-10-31T23:59:59Z"));
        assertThat(w.from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(w.to()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));

        // 12월 -> 다음 해 1월, 서울 기준으로는 이미 다음 달이어도 UTC 로 센다
        MonthlyQuotaPolicy.Window dec = MonthlyQuotaPolicy.monthOf(Instant.parse("2026-12-31T20:00:00Z"));
        assertThat(dec.from()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(dec.to()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("limit 미만이면 허용, limit 이상이면 거부 (경계 = limit)")
    void allows_boundary() {
        assertThat(MonthlyQuotaPolicy.allows(0, 10)).isTrue();
        assertThat(MonthlyQuotaPolicy.allows(9, 10)).isTrue();
        assertThat(MonthlyQuotaPolicy.allows(10, 10)).isFalse();
        assertThat(MonthlyQuotaPolicy.allows(11, 10)).isFalse();
    }
}
