package com.stagelog.account.application;

import com.stagelog.common.locale.SupportedLocale;

/**
 * 다른 기능이 보는 사용자 요약. locale 은 사용자가 언어를 고르지 않았으면(기기 언어) null, departure 는 출발지 미설정이면 null.
 */
public record AccountSnapshot(Long id, String nickname, SupportedLocale locale, boolean admin, boolean consented,
                              Departure departure) {

    public record Departure(String name, String countryCode, double lat, double lng) {
    }
}
