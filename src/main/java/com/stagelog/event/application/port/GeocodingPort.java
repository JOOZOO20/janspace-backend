package com.stagelog.event.application.port;

import com.stagelog.event.domain.GeoPoint;
import java.util.Optional;

/**
 * 장소 이름 -> 좌표. countryCode 는 ISO 3166-1 alpha-2 (null 허용). 결과 없음·키 없음은 empty.
 */
public interface GeocodingPort {

    Optional<GeoPoint> geocode(String query, String countryCode);
}
