package com.janspace.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.attendance.domain.AltitudeScale.Headline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D7 높이 헤드라인: 마지막으로 지난 기준, 다음 기준과 남은 거리, 가까움 판정.
 */
class AltitudeScaleTest {

    @Test
    @DisplayName("966km: 우주정거장(400)을 지났고 다음은 지구 하나 높이(6371)까지 5405km")
    void headline_966() {
        assertThat(AltitudeScale.headline(966)).isEqualTo(new Headline(400, 6371, 5405, false));
    }

    @Test
    @DisplayName("90km: 여객기(10)를 지났고 카르만 라인(100)까지 10km 라 가깝다")
    void headline_90() {
        assertThat(AltitudeScale.headline(90)).isEqualTo(new Headline(10, 100, 10, true));
    }

    @Test
    @DisplayName("5km: 지난 기준 없음. 달을 넘으면 다음 기준 없음")
    void headline_none_and_beyond_moon() {
        assertThat(AltitudeScale.headline(5)).isEqualTo(new Headline(null, 10, 5, false));
        assertThat(AltitudeScale.headline(400_000)).isEqualTo(new Headline(384400, null, null, false));
    }
}
