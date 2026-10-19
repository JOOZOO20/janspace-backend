package com.stagelog.event.infrastructure;

import com.stagelog.event.application.port.GeocodingPort;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.integration.kakao.KakaoLocalClient;
import com.stagelog.integration.kakao.KakaoPlace;
import com.stagelog.integration.locationiq.LocationIqClient;
import com.stagelog.integration.locationiq.LocationIqPlace;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GeocodingPort 구현. 국내(KR)이고 카카오 키가 있으면 카카오 키워드 검색, 그 외는 LocationIQ. 첫 결과를 좌표로 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeocodingAdapter implements GeocodingPort {

    private final KakaoLocalClient kakaoLocalClient;
    private final LocationIqClient locationIqClient;

    @Override
    public Optional<GeoPoint> geocode(String query, String countryCode) {
        if ("KR".equalsIgnoreCase(countryCode) && kakaoLocalClient.isEnabled()) {
            List<KakaoPlace> places = kakaoLocalClient.searchKeyword(query);
            log.info("[지오코딩 카카오] query={}, results={}", query, places.size());
            return places.stream().findFirst()
                    .map(p -> new GeoPoint(Double.parseDouble(p.y()), Double.parseDouble(p.x())));
        }
        List<LocationIqPlace> places = locationIqClient.search(query, 1);
        log.info("[지오코딩 LocationIQ] query={}, results={}", query, places.size());
        return places.stream().findFirst()
                .map(p -> new GeoPoint(Double.parseDouble(p.lat()), Double.parseDouble(p.lon())));
    }
}
