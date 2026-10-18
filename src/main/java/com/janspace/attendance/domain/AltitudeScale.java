package com.janspace.attendance.domain;

import java.util.List;

/**
 * 누적 거리를 높이로 읽는 기준 고도. 헤드라인은 마지막으로 지난 기준, 다음 기준은 가까울 때만 강조한다.
 */
public final class AltitudeScale {

    // 여객기 / 카르만 라인 / ISS / 지구 반지름 / 지구 지름 / GPS 궤도 / 정지궤도 / 달
    public static final List<Integer> MILESTONES_KM = List.of(10, 100, 400, 6371, 12742, 20200, 35786, 384400);

    // 프론트 altitude.ts 의 NEXT_NEAR_RATIO 와 같은 규칙: 남은 거리 <= 다음 기준 x 0.25 면 가깝다
    public static final double NEXT_NEAR_RATIO = 0.25;

    private AltitudeScale() {
    }

    /** passedKm 는 마지막으로 지난 기준(없으면 null), nextKm·remainingKm 는 다음 기준(달을 넘었으면 null). */
    public record Headline(Integer passedKm, Integer nextKm, Integer remainingKm, boolean nextNear) {
    }

    public static Headline headline(double totalKm) {
        Integer passed = null;
        Integer next = null;
        for (int km : MILESTONES_KM) {
            if (totalKm >= km) {
                passed = km;
            } else {
                next = km;
                break;
            }
        }
        Integer remaining = next == null ? null : (int) Math.round(next - totalKm);
        boolean near = next != null && remaining <= next * NEXT_NEAR_RATIO;
        return new Headline(passed, next, remaining, near);
    }
}
