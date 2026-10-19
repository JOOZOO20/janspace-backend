package com.stagelog.attendance.domain;

import java.util.List;

/**
 * 누적 거리를 높이로 읽는 여정 단계. 헤드라인은 마지막으로 지난 기준, 다음 기준은 가까울 때만 강조한다.
 * 프론트 lib/altitude.ts 의 MILESTONES 와 같은 값이다(기획서 7.2-1, 2026-10-06 재해석). 장면 이름은 우주 높이에서 왔지만
 * 실제 높이가 아니라 "첫 공연에 바로 오르고 해외 공연을 다녀오면 달에 닿는" 간격이다. 프론트와 함께 바꾼다.
 */
public final class AltitudeScale {

    public static final List<Integer> MILESTONES_KM = List.of(10, 60, 200, 600, 2500, 10000);

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
