package com.janspace.integration.kakao;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 로컬 주소 검색 클라이언트. Authorization: KakaoAK {key}
 */
@Component
public class KakaoLocalClient {

    private final KakaoProperties props;
    private final RestClient restClient;

    public KakaoLocalClient(KakaoProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public KakaoAddressResponse searchAddress(String query) throws RestClientException {
        return restClient.get()
                .uri(props.addressSearchUri() + "?query={q}", query)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + props.restApiKey())
                .retrieve()
                .body(KakaoAddressResponse.class);
    }
}
