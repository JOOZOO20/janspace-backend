package com.janspace.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.attendance.domain.ChainPolicy.Stop;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 연속 이동 허용: 0~5일 전 + 다른 도시 + 두 공연장 좌표.
 */
class ChainPolicyTest {

    static final LocalDate D = LocalDate.of(2026, 8, 16);

    @Test
    @DisplayName("4일 전 다른 도시 -> 허용, 같은 날 다른 도시 -> 허용")
    void eligible_cases() {
        assertThat(ChainPolicy.eligible(new Stop(D.minusDays(4), "Singapore", true), new Stop(D, "Bangkok", true))).isTrue();
        assertThat(ChainPolicy.eligible(new Stop(D, "Singapore", true), new Stop(D, "Bangkok", true))).isTrue();
        assertThat(ChainPolicy.eligible(new Stop(D.minusDays(5), "Singapore", true), new Stop(D, "Bangkok", true))).isTrue();
    }

    @Test
    @DisplayName("6일 전, 같은 도시(대소문자 무시), 좌표 없음, 직전 기록 없음, 뒤의 행사 -> 불허")
    void ineligible_cases() {
        assertThat(ChainPolicy.eligible(new Stop(D.minusDays(6), "Singapore", true), new Stop(D, "Bangkok", true))).isFalse();
        assertThat(ChainPolicy.eligible(new Stop(D.minusDays(2), "bangkok", true), new Stop(D, "Bangkok", true))).isFalse();
        assertThat(ChainPolicy.eligible(new Stop(D.minusDays(2), "Singapore", false), new Stop(D, "Bangkok", true))).isFalse();
        assertThat(ChainPolicy.eligible(new Stop(D.minusDays(2), null, true), new Stop(D, "Bangkok", true))).isFalse();
        assertThat(ChainPolicy.eligible(null, new Stop(D, "Bangkok", true))).isFalse();
        assertThat(ChainPolicy.eligible(new Stop(D.plusDays(1), "Singapore", true), new Stop(D, "Bangkok", true))).isFalse();
    }
}
