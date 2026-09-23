package com.janspace.integration.setlistfm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * setlist.fm REST 1.0. 헤더 x-api-key, Accept: application/json. 범위를 넘은 페이지는 404 를 주므로 빈 페이지로 바꾼다.
 */
@Slf4j
@Component
public class SetlistFmClient {

    private final SetlistFmProperties props;
    private final RestClient restClient;

    public SetlistFmClient(SetlistFmProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public boolean isEnabled() {
        return props.apiKey() != null && !props.apiKey().isBlank() && props.artistMbid() != null && !props.artistMbid().isBlank();
    }

    public String artistMbid() {
        return props.artistMbid();
    }

    public SetlistFmSetlist getSetlist(String id) throws RestClientException {
        return restClient.get().uri(props.baseUri() + "/setlist/{id}", id)
                .header("x-api-key", props.apiKey())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(SetlistFmSetlist.class);
    }

    public SetlistFmSetlistsPage listArtistSetlists(String mbid, int page) throws RestClientException {
        try {
            SetlistFmSetlistsPage result = restClient.get().uri(props.baseUri() + "/artist/{mbid}/setlists?p={page}", mbid, page)
                    .header("x-api-key", props.apiKey())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve().body(SetlistFmSetlistsPage.class);
            log.info("[setlist.fm 아티스트 셋리 조회] mbid={}, page={}, 결과={}", mbid, page, result == null ? 0 : result.items().size());
            return result == null ? new SetlistFmSetlistsPage(0, page, 0, java.util.List.of()) : result;
        } catch (HttpClientErrorException.NotFound e) {
            return new SetlistFmSetlistsPage(0, page, 0, java.util.List.of());
        }
    }
}
