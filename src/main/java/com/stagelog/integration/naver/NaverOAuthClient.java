package com.stagelog.integration.naver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 네이버 로그인 HTTP 클라이언트 (인가 URL, 코드 교환, 프로필). 내부 모델 변환은 account.infrastructure 가 한다.
 */
@Slf4j
@Component
public class NaverOAuthClient {

    private final NaverOAuthProperties props;
    private final RestClient restClient;

    public NaverOAuthClient(NaverOAuthProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public String authorizeUrl(String redirectUri, String state) {
        // 변수로 넣고 encode() 를 먼저 호출해야 redirect_uri 의 ':' '/' 까지 엄격히 인코딩된다
        return UriComponentsBuilder.fromUriString(props.authorizeUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("state", "{state}")
                .encode().build().expand(props.clientId(), redirectUri, state).toUriString();
    }

    // 네이버는 토큰 요청에 state 를 함께 요구한다
    public NaverTokenResponse exchangeCode(String code, String state) throws RestClientException {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("code", code);
        form.add("state", state);
        log.info("[네이버 토큰 교환 요청]");
        return restClient.post().uri(props.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(NaverTokenResponse.class);
    }

    public NaverProfileResponse fetchProfile(String accessToken) throws RestClientException {
        return restClient.get().uri(props.userinfoUri())
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(NaverProfileResponse.class);
    }
}
