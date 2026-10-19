package com.stagelog.account.infrastructure;

import com.stagelog.account.application.port.RegionSearchPort.Place;
import com.stagelog.integration.locationiq.LocationIqClient;
import com.stagelog.integration.locationiq.LocationIqPlace;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LocationIQ 검색 결과를 도시 단위 Place 로 접는다. name = "도시, 국가", countryCode 는 대문자 ISO-3166-1.
 */
@Component
@RequiredArgsConstructor
public class LocationIqRegionSearchAdapter {

    static final int LIMIT = 12;

    private final LocationIqClient client;

    public List<Place> search(String query) {
        List<Place> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (LocationIqPlace p : client.search(query, LIMIT)) {
            LocationIqPlace.Address a = p.address();
            if (a == null || a.countryCode() == null) {
                continue;
            }
            String city = first(a.city(), a.town(), a.village());
            if (city == null) {
                continue;
            }
            String name = a.country() == null ? city : city + ", " + a.country();
            if (!seen.add(name)) {
                continue;
            }
            out.add(new Place(name, a.countryCode().toUpperCase(Locale.ROOT),
                    Double.parseDouble(p.lat()), Double.parseDouble(p.lon())));
        }
        return out;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
