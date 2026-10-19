package com.stagelog.integration.kopis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 공연시설 상세(prfplc/{mt10id}). 좌표(la/lo)는 시설 단위에만 있고 mt13s 가 하위 공연장 목록이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KopisFacility(String mt10id, String fcltynm, String la, String lo, String adres, Halls mt13s) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Halls(List<KopisHall> mt13) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KopisHall(String mt13id, String prfplcnm, String seatscale) {
    }

    public List<KopisHall> hallList() {
        return mt13s == null || mt13s.mt13() == null ? List.of() : mt13s.mt13();
    }
}
