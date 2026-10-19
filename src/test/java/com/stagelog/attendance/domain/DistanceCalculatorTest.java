package com.stagelog.attendance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import com.stagelog.attendance.domain.DistanceCalculator.Point;
import com.stagelog.attendance.domain.DistanceCalculator.Stop;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 거리 규칙 D1~D6: 왕복, 설계 예시 ±3%, 첫 기록 토글, 좌표 없음, 기록별 스냅샷.
 */
class DistanceCalculatorTest {

    static final Point SEOUL = new Point(37.5665, 126.9780);
    static final Point KSPO = new Point(37.5199, 127.1233);
    static final Point SINGAPORE = new Point(1.3521, 103.8198);
    static final Point BANGKOK = new Point(13.7563, 100.5018);
    static final Point KUALA_LUMPUR = new Point(3.1390, 101.6869);

    @Test
    @DisplayName("D1 서울 시청 -> KSPO 돔 왕복 약 27.8km")
    void d1_round_trip_seoul_kspo() {
        assertThat(DistanceCalculator.roundTrip(SEOUL, KSPO)).isBetween(27.3, 28.3);
        assertThat(DistanceCalculator.chain(List.of(new Stop(SEOUL, KSPO, false)))).containsExactly(DistanceCalculator.roundTrip(SEOUL, KSPO));
    }

    @Test
    @DisplayName("D2 싱가포르·방콕·쿠알라룸푸르 전부 왕복이면 약 26,000km")
    void d2_all_round_trips() {
        List<Double> out = DistanceCalculator.chain(List.of(
                new Stop(SEOUL, SINGAPORE, false), new Stop(SEOUL, BANGKOK, false), new Stop(SEOUL, KUALA_LUMPUR, false)));
        assertThat(out.stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(26_000, withinPercentage(3));
    }

    @Test
    @DisplayName("D3 방콕·쿠알라룸푸르에 연속 이동 토글이면 약 11,900km (편도 + 마지막에만 귀가)")
    void d3_continued_chain() {
        List<Double> out = DistanceCalculator.chain(List.of(
                new Stop(SEOUL, SINGAPORE, false), new Stop(SEOUL, BANGKOK, true), new Stop(SEOUL, KUALA_LUMPUR, true)));
        assertThat(out.stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(11_900, withinPercentage(3));
        // 첫 기록은 귀가 없이 편도만, 둘째는 직전 공연장에서 편도만
        assertThat(out.get(0)).isCloseTo(DistanceCalculator.oneWay(SEOUL, SINGAPORE), withinPercentage(0.1));
        assertThat(out.get(1)).isCloseTo(DistanceCalculator.oneWay(SINGAPORE, BANGKOK), withinPercentage(0.1));
        assertThat(out.get(2)).isCloseTo(DistanceCalculator.oneWay(BANGKOK, KUALA_LUMPUR) + DistanceCalculator.oneWay(KUALA_LUMPUR, SEOUL),
                withinPercentage(0.1));
    }

    @Test
    @DisplayName("D4 첫 기록에 토글이 켜져 있어도 왕복")
    void d4_first_record_continued_is_round_trip() {
        assertThat(DistanceCalculator.chain(List.of(new Stop(SEOUL, SINGAPORE, true))))
                .containsExactly(DistanceCalculator.roundTrip(SEOUL, SINGAPORE));
    }

    @Test
    @DisplayName("D5 직전 공연장 좌표가 없으면 예외 없이 왕복, 직전 기록은 null")
    void d5_previous_venue_missing() {
        List<Double> out = DistanceCalculator.chain(List.of(new Stop(SEOUL, null, false), new Stop(SEOUL, BANGKOK, true)));
        assertThat(out.get(0)).isNull();
        assertThat(out.get(1)).isEqualTo(DistanceCalculator.roundTrip(SEOUL, BANGKOK));
        // 출발지 스냅샷이 없는 기록은 null 이지만 다음 기록은 그 공연장에서 이어붙을 수 있다
        List<Double> noHome = DistanceCalculator.chain(List.of(new Stop(null, SINGAPORE, false), new Stop(SEOUL, BANGKOK, true)));
        assertThat(noHome.get(0)).isNull();
        assertThat(noHome.get(1)).isCloseTo(DistanceCalculator.oneWay(SINGAPORE, BANGKOK) + DistanceCalculator.oneWay(BANGKOK, SEOUL),
                withinPercentage(0.1));
    }

    @Test
    @DisplayName("D6 기록마다 자기 출발지 스냅샷을 쓴다 (연속 구간의 귀가는 마지막 기록의 출발지)")
    void d6_per_record_departure_snapshot() {
        Point busan = new Point(35.1796, 129.0756);
        List<Double> out = DistanceCalculator.chain(List.of(new Stop(SEOUL, SINGAPORE, false), new Stop(busan, BANGKOK, true)));
        assertThat(out.get(0)).isCloseTo(DistanceCalculator.oneWay(SEOUL, SINGAPORE), withinPercentage(0.1));
        assertThat(out.get(1)).isCloseTo(DistanceCalculator.oneWay(SINGAPORE, BANGKOK) + DistanceCalculator.oneWay(BANGKOK, busan),
                withinPercentage(0.1));
        assertThat(DistanceCalculator.chain(List.of(new Stop(SEOUL, KSPO, false), new Stop(busan, KSPO, false))))
                .containsExactly(DistanceCalculator.roundTrip(SEOUL, KSPO), DistanceCalculator.roundTrip(busan, KSPO));
    }
}
