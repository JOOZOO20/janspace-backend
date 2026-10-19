package com.stagelog.attendance.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 대권거리(haversine)와 연속 이동 체인 계산. 순수 함수만 있다.
 */
public final class DistanceCalculator {

    // IUGG 평균 반지름
    static final double EARTH_RADIUS_KM = 6371.0088;

    private DistanceCalculator() {
    }

    public record Point(double lat, double lng) {
    }

    /** 체인의 정거장 하나 = 기록 하나. home 은 그 기록의 출발지 스냅샷, venue 는 행사 공연장 좌표. 둘 다 null 가능. */
    public record Stop(Point home, Point venue, boolean continued) {
    }

    public static double oneWay(Point a, Point b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(h));
    }

    public static double roundTrip(Point home, Point venue) {
        return 2 * oneWay(home, venue);
    }

    // 단독 기록(갈래요 등): 왕복. 좌표가 하나라도 없으면 null
    public static Double standalone(Point home, Point venue) {
        return home == null || venue == null ? null : roundTrip(home, venue);
    }

    /**
     * 행사 순으로 정렬된 기록들의 거리를 한 번에 구한다. 결과 인덱스는 입력과 같고 계산 불가면 null.
     * - 기본: 출발지 -> 공연장 -> 출발지 왕복
     * - continued 이고 직전 공연장 좌표가 있으면: 직전 공연장 -> 이번 공연장 편도. 직전 기록은 귀가 구간을 잃는다
     * - 연속 구간의 마지막 기록만 공연장 -> 출발지 귀가 구간을 더한다
     * - 첫 기록의 continued, 직전 공연장 좌표 없음은 왕복으로 취급. 이번 공연장 좌표가 없으면 null 이고 다음 기록은 이어붙지 못한다
     */
    public static List<Double> chain(List<Stop> stops) {
        int n = stops.size();
        boolean[] linked = new boolean[n];
        for (int i = 1; i < n; i++) {
            linked[i] = stops.get(i).continued() && stops.get(i - 1).venue() != null && stops.get(i).venue() != null;
        }
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Stop s = stops.get(i);
            if (s.venue() == null) {
                out.add(null);
                continue;
            }
            // 1. 들어오는 구간
            Double inbound;
            if (linked[i]) {
                inbound = oneWay(stops.get(i - 1).venue(), s.venue());
            } else {
                inbound = s.home() == null ? null : oneWay(s.home(), s.venue());
            }
            // 2. 귀가 구간은 연속 구간의 마지막에만
            boolean lastOfRun = i == n - 1 || !linked[i + 1];
            Double back = !lastOfRun ? 0.0 : s.home() == null ? null : oneWay(s.venue(), s.home());
            out.add(inbound == null || back == null ? null : inbound + back);
        }
        return out;
    }
}
