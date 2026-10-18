package com.janspace.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.janspace.support.ApiClient;
import com.janspace.support.ApiClient.Res;
import com.janspace.support.Fixtures;
import com.janspace.support.IntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GET /places: 한글은 Kakao, 그 외는 LocationIQ 로 라우팅되고 Place 계약 형태로 내려온다.
 */
class PlaceIntegrationTest extends IntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    MockRestServiceServer providers;
    @Autowired
    ObjectMapper objectMapper;

    ApiClient api;

    @BeforeEach
    void setUp() {
        providers.reset();
        api = new ApiClient(port, objectMapper);
    }

    @Test
    @DisplayName("q=노원구 -> Kakao 시군구 결과 {name, countryCode, lat, lng}")
    void korean_query_goes_to_kakao() {
        providers.expect(requestTo(Matchers.startsWith("https://dapi.kakao.com/v2/local/search/address.json")))
                .andRespond(withSuccess(Fixtures.read("places/kakao-nowon.json"), MediaType.APPLICATION_JSON));

        Res res = api.get("/api/v1/places?q=노원구", null);

        providers.verify();
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.code()).isEqualTo("OK");
        assertThat(res.data()).hasSize(1);
        JsonNode place = res.data().get(0);
        assertThat(place.path("name").asString()).isEqualTo("서울 노원구");
        assertThat(place.path("countryCode").asString()).isEqualTo("KR");
        assertThat(place.path("lat").asDouble()).isEqualTo(37.654305);
        assertThat(place.path("lng").asDouble()).isEqualTo(127.056297);
    }

    @Test
    @DisplayName("q=Bangkok -> LocationIQ 도시 결과")
    void latin_query_goes_to_locationiq() {
        providers.expect(requestTo(Matchers.startsWith("https://us1.locationiq.com/v1/search")))
                .andRespond(withSuccess(Fixtures.read("places/locationiq-bangkok.json"), MediaType.APPLICATION_JSON));

        Res res = api.get("/api/v1/places?q=Bangkok", null);

        providers.verify();
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.data()).hasSize(2);
        assertThat(res.data().get(0).path("name").asString()).isEqualTo("Bangkok, Thailand");
        assertThat(res.data().get(0).path("countryCode").asString()).isEqualTo("TH");
        assertThat(res.data().get(1).path("countryCode").asString()).isEqualTo("PH");
    }

    @Test
    @DisplayName("q 가 비면 400 INVALID_INPUT, 외부 호출 없음")
    void blank_query_is_400() {
        Res res = api.get("/api/v1/places?q=", null);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.code()).isEqualTo("INVALID_INPUT");
        providers.verify();
    }
}
