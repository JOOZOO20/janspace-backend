package com.stagelog.account.infrastructure;

import com.stagelog.account.application.port.RegionSearchPort.Place;
import com.stagelog.integration.kakao.KakaoAddressResponse;
import com.stagelog.integration.kakao.KakaoLocalClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 카카오 주소 검색 결과를 시·군·구 단위 Place 로 접는다. name = 1depth + " " + 2depth, 같은 이름은 첫 좌표만 남긴다.
 */
@Component
@RequiredArgsConstructor
public class KakaoRegionSearchAdapter {

    static final int LIMIT = 12;

    private final KakaoLocalClient client;

    public List<Place> search(String query) {
        KakaoAddressResponse response = client.searchAddress(query);
        List<Place> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (response == null || response.documents() == null) {
            return out;
        }
        for (KakaoAddressResponse.Document doc : response.documents()) {
            KakaoAddressResponse.Address a = doc.address();
            if (a == null || a.region1() == null || a.region1().isBlank()) {
                continue;
            }
            String name = a.region2() == null || a.region2().isBlank() ? a.region1() : a.region1() + " " + a.region2();
            if (!seen.add(name)) {
                continue;
            }
            out.add(new Place(name, "KR", Double.parseDouble(doc.y()), Double.parseDouble(doc.x())));
            if (out.size() >= LIMIT) {
                break;
            }
        }
        return out;
    }
}
