package com.stagelog.event.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 위경도. DB 는 DECIMAL(9,6) 이므로 소수 6자리로 맞춘다.
 */
public record GeoPoint(double lat, double lng) {

    public GeoPoint {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new IllegalArgumentException("좌표 범위를 벗어났습니다: " + lat + ", " + lng);
        }
    }

    public static GeoPoint of(BigDecimal lat, BigDecimal lng) {
        return lat == null || lng == null ? null : new GeoPoint(lat.doubleValue(), lng.doubleValue());
    }

    public BigDecimal latDecimal() {
        return BigDecimal.valueOf(lat).setScale(6, RoundingMode.HALF_UP);
    }

    public BigDecimal lngDecimal() {
        return BigDecimal.valueOf(lng).setScale(6, RoundingMode.HALF_UP);
    }
}
