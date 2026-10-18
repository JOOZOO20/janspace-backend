package com.janspace.integration.kakao;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 카카오 로컬 API 클라이언트. 주소 검색(출발지 시군구)과 키워드 장소 검색(공연장 좌표)을 제공한다.
 * Authorization: KakaoAK {key}. 키가 비어 있으면 키워드 검색은 호출하지 않고 빈 목록을 준다.
 */
@Slf4j
@Component
public class KakaoLocalClient {

    static final String KEYWORD_URL = "https://dapi.kakao.com/v2/local/search/keyword.json";

    private final KakaoProperties props;
    private final RestClient restClient;

    public KakaoLocalClient(KakaoProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    public KakaoAddressResponse searchAddress(String query) throws RestClientException {
        return restClient.get()
                .uri(props.addressSearchUri() + "?query={q}", query)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + props.restApiKey())
                .retrieve()
                .body(KakaoAddressResponse.class);
    }

    public List<KakaoPlace> searchKeyword(String query) {
        if (!isEnabled()) {
            log.info("[카카오 검색 건너뜀] KAKAO_REST_API_KEY 미설정");
            return List.of();
        }
        record Response(List<KakaoPlace> documents) {
        }
        // 외부 호출. 키는 로그에 남기지 않는다
        Response response = restClient.get()
                .uri(KEYWORD_URL + "?query={query}", query)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + props.restApiKey())
                .retrieve()
                .body(Response.class);
        return response == null || response.documents() == null ? List.of() : response.documents();
    }
}
