package com.janspace.integration.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.janspace.event.domain.GeoPoint;
import com.janspace.event.infrastructure.GeocodingAdapter;
import com.janspace.integration.locationiq.LocationIqClient;
import com.janspace.integration.locationiq.LocationIqProperties;
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
 * 카카오 키워드 검색 픽스처 매핑 (실키 없음 — 실연동 미확인).
 */
class KakaoLocalClientTest {

    @Test
    @DisplayName("Authorization: KakaoAK 헤더로 query 를 보내고 documents 를 파싱한다. 어댑터는 y=위도, x=경도로 바꾼다")
    void parses_keyword_search_fixture() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.startsWith(KakaoLocalClient.KEYWORD_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("query", "KSPO%20DOME"))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kakao/keyword.json"), MediaType.APPLICATION_JSON));
        KakaoLocalClient client = new KakaoLocalClient(new KakaoProperties("test-key", "https://dapi.kakao.com/v2/local/search/address.json"), builder);

        List<KakaoPlace> places = client.searchKeyword("KSPO DOME");

        assertThat(places).hasSize(1);
        assertThat(places.get(0).placeName()).isEqualTo("KSPO DOME");
        assertThat(places.get(0).x()).isEqualTo("127.1233");
        server.verify();

        server.reset();
        server.expect(requestTo(Matchers.startsWith(KakaoLocalClient.KEYWORD_URL)))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kakao/keyword.json"), MediaType.APPLICATION_JSON));
        GeocodingAdapter adapter = new GeocodingAdapter(client, new LocationIqClient(new LocationIqProperties("", "https://us1.locationiq.com/v1/search"), RestClient.builder()));
        assertThat(adapter.geocode("KSPO DOME", "KR")).contains(new GeoPoint(37.5199, 127.1233));
    }

    @Test
    @DisplayName("키가 비어 있으면 요청 없이 빈 목록")
    void skips_without_key() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        assertThat(new KakaoLocalClient(new KakaoProperties("", "https://dapi.kakao.com/v2/local/search/address.json"), builder).searchKeyword("x")).isEmpty();
        server.verify();
    }
}
