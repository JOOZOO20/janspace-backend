package com.stagelog.integration.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Ticketmaster Discovery 픽스처 매핑 (실키 없음 — 실연동 미확인). 아티스트 ID 는 한 번만 조회한다.
 */
class TicketmasterClientTest {

    static final String BASE = "https://app.ticketmaster.com/discovery/v2";
    static final TicketmasterProperties PROPS = new TicketmasterProperties("tm-key", BASE, "STARDUST CLUB");

    @Test
    @DisplayName("attractions.json?keyword=STARDUST CLUB 로 ID 를 찾아 캐시하고 events.json?attractionId= 로 이벤트를 읽는다")
    void resolves_attraction_once_and_lists_events() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/attractions.json?keyword=STARDUST%20CLUB&apikey=tm-key"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/ticketmaster/attractions.json"), MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2),
                        requestTo(BASE + "/events.json?attractionId=K8vZ917SDC0&apikey=tm-key&size=100"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/ticketmaster/events.json"), MediaType.APPLICATION_JSON));
        TicketmasterClient client = new TicketmasterClient(PROPS, builder);

        List<TicketmasterEvent> events = client.attractionEvents();
        client.attractionEvents();

        server.verify();
        assertThat(events).hasSize(1);
        TicketmasterEvent e = events.get(0);
        assertThat(e.id()).isEqualTo("Z698xZC2Z17q7pF");
        assertThat(e.name()).isEqualTo("STARDUST CLUB Live in Singapore");
        assertThat(e.dates().start().localDate()).isEqualTo("2026-11-15");
        assertThat(e.dates().start().localTime()).isEqualTo("20:00:00");
        assertThat(e.dates().timezone()).isEqualTo("Asia/Singapore");
        assertThat(e.dates().status().code()).isEqualTo("onsale");
        assertThat(e.firstVenue().name()).isEqualTo("The Star Theatre");
        assertThat(e.firstVenue().country().countryCode()).isEqualTo("SG");
        assertThat(e.firstVenue().location().latitude()).isEqualTo("1.3062");
        assertThat(e.images()).hasSize(3);
        assertThat(e.priceRanges().get(0).currency()).isEqualTo("SGD");
    }

    @Test
    @DisplayName("아티스트 검색 결과가 없으면 이벤트 조회 없이 빈 목록")
    void empty_without_attraction() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/attractions.json")))
                .andRespond(withSuccess("{\"page\":{\"totalElements\":0}}", MediaType.APPLICATION_JSON));

        assertThat(new TicketmasterClient(PROPS, builder).attractionEvents()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("키워드 검색 결과 중 이름이 키워드와 같은 attraction 만 쓴다 (부분 일치한 다른 아티스트를 캐시하지 않음)")
    void picks_attraction_with_matching_name() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/attractions.json")))
                .andRespond(withSuccess("{\"_embedded\":{\"attractions\":[{\"id\":\"other\",\"name\":\"STARDUST CLUB Tribute Band\"},"
                        + "{\"id\":\"K8vZ917SDC0\",\"name\":\"Stardust Club\"}]}}", MediaType.APPLICATION_JSON));

        assertThat(new TicketmasterClient(PROPS, builder).findAttractionId()).contains("K8vZ917SDC0");
        server.verify();
    }

    @Test
    @DisplayName("키나 아티스트 검색어 중 하나라도 비면 비활성 (설정 누락 시 동기화를 건너뛴다)")
    void disabled_without_key_or_keyword() {
        assertThat(new TicketmasterClient(PROPS, RestClient.builder()).isEnabled()).isTrue();
        assertThat(new TicketmasterClient(new TicketmasterProperties("tm-key", BASE, ""), RestClient.builder()).isEnabled()).isFalse();
        assertThat(new TicketmasterClient(new TicketmasterProperties("", BASE, "STARDUST CLUB"), RestClient.builder()).isEnabled()).isFalse();
    }
}
