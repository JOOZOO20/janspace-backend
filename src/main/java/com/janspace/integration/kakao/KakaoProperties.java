package com.janspace.integration.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 로컬 API 설정 (janspace.kakao). restApiKey 가 비어 있으면 국내 검색 경로를 쓰지 않는다.
 */
@ConfigurationProperties(prefix = "janspace.kakao")
public record KakaoProperties(String restApiKey, String addressSearchUri) {

    public boolean enabled() {
        return restApiKey != null && !restApiKey.isBlank();
    }
}
