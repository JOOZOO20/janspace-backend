package com.janspace.account.application.port;

import java.util.List;

/**
 * 출발지 검색 포트. 국내 시군구(Kakao)·해외 도시(LocationIQ) 라우팅은 어댑터가 맡는다.
 */
public interface RegionSearchPort {

    List<Place> search(String query);

    record Place(String name, String countryCode, double lat, double lng) {
    }
}
