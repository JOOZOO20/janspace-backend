package com.stagelog.integration.google;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 구글 로그인 HTTP 클라이언트 (인가 URL, 코드 교환, userinfo).
 */
@Slf4j
@Component
public class GoogleOAuthClient {

    private final GoogleOAuthProperties props;
    private final RestClient restClient;

    public GoogleOAuthClient(GoogleOAuthProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    public String authorizeUrl(String redirectUri, String state) {
        // 변수로 넣고 encode() 를 먼저 호출해야 redirect_uri 의 ':' '/' 까지 엄격히 인코딩된다
        return UriComponentsBuilder.fromUriString(props.authorizeUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("scope", "{scope}")
                .queryParam("state", "{state}")
                .encode().build().expand(props.clientId(), redirectUri, props.scope(), state).toUriString();
    }

    // 구글은 토큰 요청에 인가 때와 같은 redirect_uri 를 요구한다
    public GoogleTokenResponse exchangeCode(String code, String redirectUri) throws RestClientException {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        log.info("[구글 토큰 교환 요청]");
        return restClient.post().uri(props.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(GoogleTokenResponse.class);
    }

    public GoogleUserInfoResponse fetchUserInfo(String accessToken) throws RestClientException {
        return restClient.get().uri(props.userinfoUri())
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(GoogleUserInfoResponse.class);
    }
}
