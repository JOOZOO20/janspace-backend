package com.janspace.integration.setlistfm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * setlist.fm REST 1.0 클라이언트. 헤더 x-api-key + Accept: application/json. 4xx/5xx 는 RestClientResponseException 으로 던진다
 * (없는 셋리는 HttpClientErrorException.NotFound). 요청 제한(분당)은 실연동에서 확인한다.
 */
@Slf4j
@Component
public class SetlistFmClient {

    public static final String BASE_URL = "https://api.setlist.fm/rest/1.0";
    static final String API_KEY_HEADER = "x-api-key";

    private final RestClient restClient;
    private final String apiKey;

    public SetlistFmClient(RestClient.Builder builder, @Value("${janspace.setlistfm.api-key:}") String apiKey) {
        this.restClient = builder.build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public SetlistFmSetlist getSetlist(String id) throws RestClientException {
        log.info("[setlist.fm 셋리 조회] id={}", id);
        return restClient.get().uri(BASE_URL + "/setlist/{id}", id)
                .header(API_KEY_HEADER, apiKey)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .body(SetlistFmSetlist.class);
    }

    // page 는 1 부터
    public SetlistFmSetlistsPage listArtistSetlists(String mbid, int page) throws RestClientException {
        log.info("[setlist.fm 아티스트 셋리 목록] mbid={}, page={}", mbid, page);
        return restClient.get().uri(BASE_URL + "/artist/{mbid}/setlists?p={page}", mbid, page)
                .header(API_KEY_HEADER, apiKey)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .body(SetlistFmSetlistsPage.class);
    }
}
