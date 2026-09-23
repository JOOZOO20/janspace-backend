package com.janspace.integration.locationiq;

import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * LocationIQ 지오코딩 검색 클라이언트. 최대 12건, 주소 상세 포함.
 */
@Component
public class LocationIqClient {

    private static final ParameterizedTypeReference<List<LocationIqPlace>> LIST = new ParameterizedTypeReference<>() {
    };

    private final LocationIqProperties props;
    private final RestClient restClient;

    public LocationIqClient(LocationIqProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public List<LocationIqPlace> search(String query, int limit) throws RestClientException {
        List<LocationIqPlace> body = restClient.get()
                .uri(props.searchUri() + "?key={key}&q={q}&format=json&addressdetails=1&limit={limit}",
                        props.token(), query, limit)
                .retrieve()
                .body(LIST);
        return body == null ? List.of() : body;
    }
}
