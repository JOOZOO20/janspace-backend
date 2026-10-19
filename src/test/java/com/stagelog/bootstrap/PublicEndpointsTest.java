package com.stagelog.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// Swagger 자물쇠 판정이 SecurityConfig 의 규칙 순서(공개 > 관리자 > 공개 GET > 로그인 POST)와 같은지 확인한다
class PublicEndpointsTest {

    @ParameterizedTest
    @CsvSource({
            "GET, /api/v1/events/{id}, true",
            "GET, /api/v1/me, true",
            "POST, /api/v1/auth/token, true",
            "GET, /api/v1/auth/{provider}/callback, true",
            "GET, /api/v1/admin/youtube/callback, true",
            "GET, /swagger-ui/index.html, true",
            "GET, /actuator/health, true",
            "GET, /api/v1/admin/sync-logs, false",
            "POST, /api/v1/admin/events, false",
            "PATCH, /api/v1/me, false",
            "DELETE, /api/v1/me, false",
            "POST, /api/v1/events/{id}/going, false",
            "PUT, /api/v1/auth/token, false"
    })
    void matches_security_rules(String method, String path, boolean expected) {
        assertThat(PublicEndpoints.isPublic(method, path)).isEqualTo(expected);
    }
}
