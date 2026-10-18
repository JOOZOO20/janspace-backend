package com.janspace.integration.setlistfm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * setlist.fm 픽스처 매핑 (실키 없음 — 실연동 미확인). x-api-key·Accept 헤더, 페이지·곡·좌표, 범위 밖 페이지 404.
 */
class SetlistFmClientTest {

    static final String BASE = "https://api.setlist.fm/rest/1.0";
    static final SetlistFmProperties PROPS = new SetlistFmProperties("sf-key", "0f7e6a3c-1b2d-4e5f-8a9b-0c1d2e3f4a5b", BASE);

    @Test
    @DisplayName("artist/{mbid}/setlists?p=1: 셋리 2건, 곡 이름·커버·도시 좌표(long)·국가 코드")
    void lists_artist_setlists() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/artist/0f7e6a3c-1b2d-4e5f-8a9b-0c1d2e3f4a5b/setlists?p=1"))
                .andExpect(header("x-api-key", "sf-key"))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(new ClassPathResource("fixtures/setlistfm/artist-setlists-sync.json"), MediaType.APPLICATION_JSON));

        SetlistFmSetlistsPage page = new SetlistFmClient(PROPS, builder).listArtistSetlists(PROPS.artistMbid(), 1);

        server.verify();
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(2);
        SetlistFmSetlist first = page.items().get(0);
        assertThat(first.id()).isEqualTo("63b2f4a1");
        assertThat(first.eventDate()).isEqualTo("23-10-2026");
        assertThat(first.venue().name()).isEqualTo("KSPO Dome");
        assertThat(first.venue().city().country().code()).isEqualTo("KR");
        assertThat(first.venue().city().coords().lng()).isEqualTo(126.9997222);
        assertThat(first.songs()).extracting(SetlistFmSetlist.Song::name)
                .containsExactly("for lovers who hesitate", "bad dreams", "LEGEND", "Twilight Zone");
        assertThat(first.songs().get(3).cover().name()).isEqualTo("Golden Earring");
        assertThat(page.items().get(1).songs()).isEmpty();
    }

    @Test
    @DisplayName("setlist/{id} 단건 조회, 범위 밖 페이지 404 는 빈 페이지")
    void get_setlist_and_404_page() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/setlist/63b2f4a1"))
                .andExpect(header("x-api-key", "sf-key"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/setlistfm/setlist-63b2f4a1.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/artist/m/setlists?p=99")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        SetlistFmClient client = new SetlistFmClient(PROPS, builder);

        assertThat(client.getSetlist("63b2f4a1").songs()).hasSize(3);
        assertThat(client.listArtistSetlists("m", 99).items()).isEmpty();
        server.verify();
    }
}
