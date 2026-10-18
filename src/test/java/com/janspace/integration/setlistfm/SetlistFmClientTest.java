package com.janspace.integration.setlistfm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.janspace.setlist.application.port.FmSetlist;
import com.janspace.setlist.infrastructure.SetlistFmSetlistAdapter;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * setlist.fm 픽스처 매핑 (실키 없음 — 실연동 미확인). 곡명은 setlist.fm 의 제멋대로인 표기 그대로 온다.
 */
class SetlistFmClientTest {

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    SetlistFmClient client = new SetlistFmClient(builder, "k");

    @Test
    @DisplayName("GET /setlist/{id}: x-api-key·Accept 헤더, 세트 순서대로 곡명, dd-MM-yyyy 날짜, 공연장·좌표 매핑")
    void get_setlist_maps_fixture() {
        server.expect(requestTo(SetlistFmClient.BASE_URL + "/setlist/63a1b2c3"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", "k"))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(new ClassPathResource("fixtures/setlistfm/setlist.json"), MediaType.APPLICATION_JSON));

        SetlistFmSetlist setlist = client.getSetlist("63a1b2c3");

        assertThat(setlist.id()).isEqualTo("63a1b2c3");
        assertThat(setlist.versionId()).isEqualTo("g3b7a1c2");
        assertThat(setlist.eventDate()).isEqualTo("23-10-2026");
        assertThat(setlist.eventLocalDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(setlist.songNames()).containsExactly("for lovers who hesitate", "bad dreams", "LEGEND", "Summer", "Yesterday");
        assertThat(setlist.venue().name()).isEqualTo("KSPO Dome");
        assertThat(setlist.venue().city().country().code()).isEqualTo("KR");
        assertThat(setlist.venue().city().coords().lng()).isEqualTo(126.978);
        assertThat(setlist.sets().set().get(1).song().get(1).cover().name()).isEqualTo("The Beatles");
        server.verify();
    }

    @Test
    @DisplayName("GET /artist/{mbid}/setlists?p=: 페이지 메타와 셋리 목록. 빈 세트는 곡 0개")
    void list_artist_setlists() {
        server.expect(requestTo(SetlistFmClient.BASE_URL + "/artist/5e8a1b2c-3d4e-4f60-9a7b-8c9d0e1f2a3b/setlists?p=1"))
                .andExpect(header("x-api-key", "k"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/setlistfm/artist-setlists.json"), MediaType.APPLICATION_JSON));

        SetlistFmSetlistsPage page = client.listArtistSetlists("5e8a1b2c-3d4e-4f60-9a7b-8c9d0e1f2a3b", 1);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.itemsPerPage()).isEqualTo(20);
        assertThat(page.setlists()).extracting(SetlistFmSetlist::id).containsExactly("63a1b2c3", "1bd5e0f4");
        assertThat(page.setlists().get(1).songNames()).isEmpty();
        assertThat(page.setlists().get(1).venue().city().country().code()).isEqualTo("TH");
        server.verify();
    }

    @Test
    @DisplayName("어댑터: FmSetlist(id, versionId, 날짜, 곡명) 로 줄이고, 404 는 empty")
    void adapter_maps_and_handles_404() {
        server.expect(requestTo(SetlistFmClient.BASE_URL + "/setlist/63a1b2c3"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/setlistfm/setlist.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(SetlistFmClient.BASE_URL + "/setlist/missing")).andRespond(withResourceNotFound());
        SetlistFmSetlistAdapter adapter = new SetlistFmSetlistAdapter(client);

        Optional<FmSetlist> found = adapter.fetch("63a1b2c3");
        Optional<FmSetlist> missing = adapter.fetch("missing");

        assertThat(found).isPresent();
        assertThat(found.get().versionId()).isEqualTo("g3b7a1c2");
        assertThat(found.get().eventDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(found.get().songNames()).hasSize(5).startsWith("for lovers who hesitate");
        assertThat(missing).isEmpty();
        server.verify();
    }
}
