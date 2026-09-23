package com.janspace.integration.locationiq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.janspace.event.domain.GeoPoint;
import com.janspace.event.infrastructure.GeocodingAdapter;
import com.janspace.integration.kakao.KakaoLocalClient;
import com.janspace.integration.kakao.KakaoProperties;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * LocationIQ 검색 픽스처 매핑 (실키 없음 — 실연동 미확인).
 */
class LocationIqClientTest {

    @Test
    @DisplayName("key·q·format=json·limit 쿼리로 GET 하고 배열을 파싱한다. 해외(KR 아님)는 어댑터가 LocationIQ 를 쓴다")
    void parses_search_fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.startsWith(LocationIqClient.SEARCH_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("key", "tok"))
                .andExpect(queryParam("q", "Impact%20Arena"))
                .andExpect(queryParam("format", "json"))
                .andExpect(queryParam("limit", "1"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/locationiq/search.json"), MediaType.APPLICATION_JSON));
        LocationIqClient client = new LocationIqClient(new LocationIqProperties("tok", "https://us1.locationiq.com/v1/search"), builder);

        List<LocationIqPlace> places = client.search("Impact Arena", 1);

        assertThat(places).hasSize(1);
        assertThat(places.get(0).displayName()).startsWith("Impact Arena");
        server.verify();

        server.reset();
        server.expect(requestTo(Matchers.startsWith(LocationIqClient.SEARCH_URL)))
                .andRespond(withSuccess(new ClassPathResource("fixtures/locationiq/search.json"), MediaType.APPLICATION_JSON));
        GeocodingAdapter adapter = new GeocodingAdapter(new KakaoLocalClient(new KakaoProperties("kakao-key", "https://dapi.kakao.com/v2/local/search/address.json"), RestClient.builder()), client);
        assertThat(adapter.geocode("Impact Arena", "TH")).contains(new GeoPoint(13.7563, 100.5018));
        server.verify();
    }

    @Test
    @DisplayName("토큰이 비어 있으면 요청 없이 빈 목록. 카카오 키도 없으면 국내도 LocationIQ 경로로 가되 빈 결과")
    void skips_without_token() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LocationIqClient client = new LocationIqClient(new LocationIqProperties(" ", "https://us1.locationiq.com/v1/search"), builder);

        assertThat(client.search("x", 1)).isEmpty();
        assertThat(new GeocodingAdapter(new KakaoLocalClient(new KakaoProperties("", "https://dapi.kakao.com/v2/local/search/address.json"), builder), client).geocode("KSPO DOME", "KR")).isEmpty();
        server.verify();
    }
}
