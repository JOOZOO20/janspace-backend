package com.stagelog.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * 보안 규칙 스모크: health 공개, /api/v1 GET 공개(없는 경로는 404 봉투), 쓰기는 401 봉투.
 */
class SmokeIntegrationTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("GET /actuator/health 는 인증 없이 200")
    void health_is_public() {
        ResponseEntity<String> res = call(HttpMethod.GET, "/actuator/health");
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("GET /api/v1 의 없는 경로는 404 봉투")
    void missing_read_is_404_envelope() {
        ResponseEntity<String> res = call(HttpMethod.GET, "/api/v1/anything-missing");
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(res.getHeaders().getContentType())).isTrue();
        assertThat(res.getBody()).contains("\"code\":\"NOT_FOUND\"").contains("\"data\":null");
    }

    @Test
    @DisplayName("인증 없는 POST /api/v1 은 401 봉투")
    void unauthenticated_write_is_401_envelope() {
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/anything");
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(MediaType.APPLICATION_JSON.isCompatibleWith(res.getHeaders().getContentType())).isTrue();
        assertThat(res.getBody()).contains("\"code\":\"UNAUTHORIZED\"").contains("\"data\":null");
    }

    // 4xx 도 예외 없이 응답 그대로 받는다
    private ResponseEntity<String> call(HttpMethod method, String path) {
        return RestClient.create("http://localhost:" + port).method(method).uri(path)
                .contentType(MediaType.APPLICATION_JSON).body("{}")
                .exchange((req, res) -> new ResponseEntity<>(res.bodyTo(String.class), res.getHeaders(), res.getStatusCode()));
    }
}
