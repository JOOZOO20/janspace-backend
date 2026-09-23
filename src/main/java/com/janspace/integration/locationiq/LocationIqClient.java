package com.janspace.integration.locationiq;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * LocationIQ 지오코딩 검색 클라이언트. 주소 상세 포함. 토큰이 비어 있으면 호출하지 않고 빈 목록을 준다.
 */
@Slf4j
@Component
public class LocationIqClient {

    /** 기본 검색 URL. 실제 호출 URL 은 janspace.locationiq.search-uri 설정을 따른다 */
    public static final String SEARCH_URL = "https://us1.locationiq.com/v1/search";

    private static final ParameterizedTypeReference<List<LocationIqPlace>> LIST = new ParameterizedTypeReference<>() {
    };

    private final LocationIqProperties props;
    private final RestClient restClient;

    public LocationIqClient(LocationIqProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public boolean isEnabled() {
        return props.token() != null && !props.token().isBlank();
    }

    public List<LocationIqPlace> search(String query, int limit) throws RestClientException {
        if (!isEnabled()) {
            log.info("[LocationIQ 검색 건너뜀] LOCATIONIQ_TOKEN 미설정");
            return List.of();
        }
        // 외부 호출. 토큰은 쿼리에 실리므로 URL 을 로그에 남기지 않는다
        List<LocationIqPlace> body = restClient.get()
                .uri(props.searchUri() + "?key={key}&q={q}&format=json&addressdetails=1&limit={limit}",
                        props.token(), query, limit)
                .retrieve()
                .body(LIST);
        return body == null ? List.of() : body;
    }
}
