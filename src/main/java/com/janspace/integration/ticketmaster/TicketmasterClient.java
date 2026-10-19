package com.janspace.integration.ticketmaster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Ticketmaster Discovery API v2. 아티스트(attraction) ID 는 키워드로 한 번 찾아 메모리에 캐시하고, 그 ID 의 이벤트를 조회한다.
 */
@Slf4j
@Component
public class TicketmasterClient {

    private final TicketmasterProperties props;
    private final RestClient restClient;
    // 한계: 프로세스 수명 동안 한 번만 해석. 아티스트 ID 가 바뀌면 재기동
    private volatile String attractionId;

    public TicketmasterClient(TicketmasterProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public boolean isEnabled() {
        return props.apiKey() != null && !props.apiKey().isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AttractionsResponse(@JsonProperty("_embedded") Embedded embedded) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Embedded(List<Attraction> attractions) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Attraction(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventsResponse(@JsonProperty("_embedded") Embedded embedded) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Embedded(List<TicketmasterEvent> events) {
        }
    }

    // keyword 와 이름이 같은(대소문자 무시) 첫 attraction. 키워드 검색은 부분 일치라 다른 아티스트가 먼저 올 수 있고,
    // 찾은 ID 는 프로세스 수명 동안 캐시되므로 이름을 확인한다. 없으면 empty (캐시하지 않음)
    public Optional<String> findAttractionId() throws RestClientException {
        if (attractionId != null) {
            return Optional.of(attractionId);
        }
        AttractionsResponse response = restClient.get()
                .uri(props.baseUri() + "/attractions.json?keyword={keyword}&apikey={key}", props.keyword(), props.apiKey())
                .retrieve().body(AttractionsResponse.class);
        List<Attraction> attractions = response == null || response.embedded() == null || response.embedded().attractions() == null
                ? List.of() : response.embedded().attractions();
        log.info("[Ticketmaster 아티스트 검색] keyword={}, 결과={}", props.keyword(), attractions.size());
        Optional<String> found = attractions.stream()
                .filter(a -> a.id() != null && props.keyword().equalsIgnoreCase(a.name()))
                .map(Attraction::id)
                .findFirst();
        if (found.isEmpty() && !attractions.isEmpty()) {
            log.warn("[Ticketmaster 아티스트 이름 불일치] keyword={}, 첫 결과={}", props.keyword(), attractions.get(0).name());
        }
        found.ifPresent(id -> attractionId = id);
        return found;
    }

    // 한계: 첫 페이지 100건만. 잔나비 예정 공연이 100건을 넘을 일은 없다
    public List<TicketmasterEvent> attractionEvents() throws RestClientException {
        Optional<String> id = findAttractionId();
        if (id.isEmpty()) {
            return List.of();
        }
        EventsResponse response = restClient.get()
                .uri(props.baseUri() + "/events.json?attractionId={id}&apikey={key}&size=100", id.get(), props.apiKey())
                .retrieve().body(EventsResponse.class);
        List<TicketmasterEvent> events = response == null || response.embedded() == null || response.embedded().events() == null
                ? List.of() : response.embedded().events();
        log.info("[Ticketmaster 이벤트 조회] attractionId={}, 결과={}", id.get(), events.size());
        return events;
    }
}
