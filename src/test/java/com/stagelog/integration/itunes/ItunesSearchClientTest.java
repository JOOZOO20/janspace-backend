package com.stagelog.integration.itunes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기록된 iTunes 응답 픽스처로 요청 URL 과 필드 매핑을 검증한다. 실제 응답은 text/javascript 로 온다.
 */
class ItunesSearchClientTest {

    @Test
    @DisplayName("term 은 URL 인코딩되고 trackId·trackName·collectionId·collectionName·artworkUrl100·releaseDate 가 매핑된다")
    void maps_fixture() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String fixture = new ClassPathResource("fixtures/itunes/search-stardust-club.json").getContentAsString(StandardCharsets.UTF_8);
        server.expect(requestTo(containsString("https://itunes.apple.com/search?term=%EC%8A%A4%ED%83%80%EB%8D%94%EC%8A%A4%ED%8A%B8%20%ED%81%B4%EB%9F%BD&entity=song&country=kr&limit=10")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.parseMediaType("text/javascript;charset=utf-8")));
        ItunesSearchClient client = new ItunesSearchClient(builder, JsonMapper.builder().build());

        List<ItunesTrack> tracks = client.search("스타더스트 클럽", "kr", 10);

        server.verify();
        assertThat(tracks).hasSize(3);
        ItunesTrack first = tracks.get(0);
        assertThat(first.trackId()).isEqualTo(1900000101L);
        assertThat(first.trackName()).isEqualTo("떠도는 별들을 위해");
        assertThat(first.collectionId()).isEqualTo(1900000100L);
        assertThat(first.collectionName()).isEqualTo("등대");
        assertThat(first.artworkUrl100()).endsWith("/100x100bb.jpg");
        assertThat(first.releaseDate()).isEqualTo("2021-03-11T12:00:00Z");
        assertThat(tracks.get(2).releaseDate()).isNull();
    }
}
