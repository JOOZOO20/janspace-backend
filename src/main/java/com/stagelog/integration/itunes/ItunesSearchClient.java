package com.stagelog.integration.itunes;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * iTunes Search API 클라이언트 (키 불필요). 응답 Content-Type 이 text/javascript 라 문자열로 받아 직접 파싱한다.
 * https://itunes.apple.com/search?term=&entity=song&country=kr&limit=10
 */
@Slf4j
@Component
public class ItunesSearchClient {

    private static final String SEARCH_URL = "https://itunes.apple.com/search?term={term}&entity=song&country={country}&limit={limit}";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ItunesSearchClient(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
    }

    public List<ItunesTrack> search(String term, String country, int limit) {
        String body = restClient.get()
                .uri(SEARCH_URL, term, country, limit)
                .retrieve()
                .body(String.class);
        ItunesSearchResponse response = body == null ? null : objectMapper.readValue(body, ItunesSearchResponse.class);
        List<ItunesTrack> results = response == null || response.results() == null ? List.of() : response.results();
        log.info("[iTunes 검색] term={}, country={}, 결과={}", term, country, results.size());
        return results;
    }
}
