package com.stagelog.account.infrastructure;

import com.stagelog.account.application.port.RegionSearchPort;
import com.stagelog.integration.kakao.KakaoProperties;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RegionSearchPort 구현. 카카오 키가 있고 질의에 한글이 있으면 국내(Kakao), 아니면 해외(LocationIQ).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegionSearchRouter implements RegionSearchPort {

    private final KakaoProperties kakaoProperties;
    private final KakaoRegionSearchAdapter kakao;
    private final LocationIqRegionSearchAdapter locationIq;

    @Override
    public List<Place> search(String query) {
        boolean domestic = kakaoProperties.enabled() && containsHangul(query);
        log.info("[출발지 검색] route={}, queryLength={}", domestic ? "kakao" : "locationiq", query.length());
        return domestic ? kakao.search(query) : locationIq.search(query);
    }

    static boolean containsHangul(String s) {
        return s.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL);
    }
}
