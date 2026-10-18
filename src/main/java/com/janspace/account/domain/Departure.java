package com.janspace.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 출발지 값 객체. users 의 departure_* 컬럼에 펼쳐진다. 네 컬럼이 모두 NULL 이면 JPA 가 embedded 자체를 null 로 읽는다.
 */
@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Departure {

    @Column(name = "departure_name", length = 100)
    private String name;

    @Column(name = "departure_country_code", length = 2)
    private String countryCode;

    @Column(name = "departure_lat", precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "departure_lng", precision = 9, scale = 6)
    private BigDecimal lng;

    public Departure(String name, String countryCode, double lat, double lng) {
        this.name = name;
        this.countryCode = countryCode;
        this.lat = BigDecimal.valueOf(lat);
        this.lng = BigDecimal.valueOf(lng);
    }
}
