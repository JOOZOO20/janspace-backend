package com.stagelog.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.account.application.port.RegionSearchPort.Place;
import com.stagelog.integration.kakao.KakaoLocalClient;
import com.stagelog.integration.kakao.KakaoProperties;
import com.stagelog.integration.locationiq.LocationIqClient;
import com.stagelog.integration.locationiq.LocationIqProperties;
import com.stagelog.support.Fixtures;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 기록된 응답 픽스처로 Kakao·LocationIQ 어댑터 매핑을 검증한다.
 */
class RegionSearchAdapterTest {

    @Test
    @DisplayName("Kakao: 시군구 이름으로 접고 중복을 없애며 x=lng, y=lat")
    void kakao_maps_to_district_level() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.startsWith("https://dapi.kakao.com/v2/local/search/address.json")))
                .andExpect(queryParam("query", "%EB%85%B8%EC%9B%90%EA%B5%AC"))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(Fixtures.read("places/kakao-nowon.json"), MediaType.APPLICATION_JSON));
        KakaoProperties props = new KakaoProperties("test-key", "https://dapi.kakao.com/v2/local/search/address.json");

        List<Place> places = new KakaoRegionSearchAdapter(new KakaoLocalClient(props, builder)).search("노원구");

        server.verify();
        assertThat(places).containsExactly(new Place("서울 노원구", "KR", 37.654305, 127.056297));
    }

    @Test
    @DisplayName("LocationIQ: 도시+국가 이름, 국가 코드 대문자, 같은 도시는 하나로")
    void locationiq_maps_to_city_level() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.startsWith("https://us1.locationiq.com/v1/search")))
                .andExpect(queryParam("q", "Bangkok"))
                .andExpect(queryParam("key", "tok"))
                .andExpect(queryParam("limit", "12"))
                .andExpect(queryParam("addressdetails", "1"))
                .andRespond(withSuccess(Fixtures.read("places/locationiq-bangkok.json"), MediaType.APPLICATION_JSON));
        LocationIqProperties props = new LocationIqProperties("tok", "https://us1.locationiq.com/v1/search");

        List<Place> places = new LocationIqRegionSearchAdapter(new LocationIqClient(props, builder)).search("Bangkok");

        server.verify();
        assertThat(places).containsExactly(
                new Place("Bangkok, Thailand", "TH", 13.7524938, 100.4935089),
                new Place("Bangkok, Philippines", "PH", 15.5327, 121.0058));
    }

    @Test
    @DisplayName("라우터: 한글 포함 + 카카오 키 있음 -> Kakao, 아니면 LocationIQ")
    void router_picks_by_hangul_and_key() {
        assertThat(RegionSearchRouter.containsHangul("노원구")).isTrue();
        assertThat(RegionSearchRouter.containsHangul("Seoul 노원")).isTrue();
        assertThat(RegionSearchRouter.containsHangul("Bangkok")).isFalse();
        assertThat(new KakaoProperties("", "x").enabled()).isFalse();
        assertThat(new KakaoProperties(null, "x").enabled()).isFalse();
        assertThat(new KakaoProperties("k", "x").enabled()).isTrue();
    }
}
