package com.stagelog.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.attendance.domain.AltitudeScale.Headline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D7 높이 헤드라인: 마지막으로 지난 기준, 다음 기준과 남은 거리, 가까움 판정. 기준은 프론트 altitude.ts 와 같은 여정 단계.
 */
class AltitudeScaleTest {

    @Test
    @DisplayName("기준은 프론트 MILESTONES 와 같은 10·60·200·600·2500·10000km")
    void milestones_match_front() {
        assertThat(AltitudeScale.MILESTONES_KM).containsExactly(10, 60, 200, 600, 2500, 10000);
    }

    @Test
    @DisplayName("966km: 600 을 지났고 다음 2500 까지 1534km, 가깝지 않다")
    void headline_966() {
        assertThat(AltitudeScale.headline(966)).isEqualTo(new Headline(600, 2500, 1534, false));
    }

    @Test
    @DisplayName("50km: 10 을 지났고 60 까지 10km 라 가깝다 (남은 거리 <= 60 x 0.25)")
    void headline_50() {
        assertThat(AltitudeScale.headline(50)).isEqualTo(new Headline(10, 60, 10, true));
    }

    @Test
    @DisplayName("5km: 지난 기준 없음. 달(10000)을 넘으면 다음 기준 없음, 딱 10000 이면 달을 지난 것")
    void headline_none_and_beyond_moon() {
        assertThat(AltitudeScale.headline(5)).isEqualTo(new Headline(null, 10, 5, false));
        assertThat(AltitudeScale.headline(10_000)).isEqualTo(new Headline(10000, null, null, false));
        assertThat(AltitudeScale.headline(400_000)).isEqualTo(new Headline(10000, null, null, false));
    }
}
