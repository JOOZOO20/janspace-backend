package com.janspace.integration.itunes;

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
        String fixture = new ClassPathResource("fixtures/itunes/search-jannabi.json").getContentAsString(StandardCharsets.UTF_8);
        server.expect(requestTo(containsString("https://itunes.apple.com/search?term=%EC%9E%94%EB%82%98%EB%B9%84&entity=song&country=kr&limit=10")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture, MediaType.parseMediaType("text/javascript;charset=utf-8")));
        ItunesSearchClient client = new ItunesSearchClient(builder, JsonMapper.builder().build());

        List<ItunesTrack> tracks = client.search("잔나비", "kr", 10);

        server.verify();
        assertThat(tracks).hasSize(3);
        ItunesTrack first = tracks.get(0);
        assertThat(first.trackId()).isEqualTo(1456790871L);
        assertThat(first.trackName()).isEqualTo("주저하는 연인들을 위해");
        assertThat(first.collectionId()).isEqualTo(1456790868L);
        assertThat(first.collectionName()).isEqualTo("전설");
        assertThat(first.artworkUrl100()).endsWith("/100x100bb.jpg");
        assertThat(first.releaseDate()).isEqualTo("2019-03-13T12:00:00Z");
        assertThat(tracks.get(2).releaseDate()).isNull();
    }
}
