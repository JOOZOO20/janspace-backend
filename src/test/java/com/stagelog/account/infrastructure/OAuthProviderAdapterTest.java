package com.stagelog.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.account.application.port.OAuthProviderPort.ProviderProfile;
import com.stagelog.account.domain.SocialProvider;
import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.integration.google.GoogleOAuthClient;
import com.stagelog.integration.google.GoogleOAuthProperties;
import com.stagelog.integration.naver.NaverOAuthClient;
import com.stagelog.integration.naver.NaverOAuthProperties;
import com.stagelog.support.Fixtures;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 제공자 토큰·프로필 픽스처로 인가 URL 과 ProviderProfile 매핑을 검증한다.
 */
class OAuthProviderAdapterTest {

    static final String REDIRECT = "http://localhost:8080/api/v1/auth/naver/callback";

    RestClient.Builder builder;
    MockRestServiceServer server;
    OAuthProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        NaverOAuthProperties naver = new NaverOAuthProperties("naver-id", "naver-secret",
                "https://nid.naver.com/oauth2.0/authorize", "https://nid.naver.com/oauth2.0/token",
                "https://openapi.naver.com/v1/nid/me");
        GoogleOAuthProperties google = new GoogleOAuthProperties("google-id", "google-secret",
                "https://accounts.google.com/o/oauth2/v2/auth", "https://oauth2.googleapis.com/token",
                "https://openidconnect.googleapis.com/v1/userinfo", "openid email profile");
        adapter = new OAuthProviderAdapter(new NaverOAuthClient(naver, builder), new GoogleOAuthClient(google, builder));
    }

    @Test
    @DisplayName("네이버: 토큰 교환(form, state 포함) 후 /v1/nid/me 의 response.id/email/nickname 을 매핑")
    void naver_profile_mapping() {
        server.expect(requestTo("https://nid.naver.com/oauth2.0/token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(Matchers.containsString("grant_type=authorization_code")))
                .andExpect(content().string(Matchers.containsString("code=abc")))
                .andExpect(content().string(Matchers.containsString("state=st")))
                .andRespond(withSuccess(Fixtures.read("oauth/naver-token.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openapi.naver.com/v1/nid/me"))
                .andExpect(header("Authorization", "Bearer AAAAQosjWDJieBiQZc3to9YQp6HDLvrmyKC-6-iZ3gq7qrkqf50ljZC-Lgoqrg"))
                .andRespond(withSuccess(Fixtures.read("oauth/naver-userinfo-n1.json"), MediaType.APPLICATION_JSON));

        ProviderProfile profile = adapter.fetchProfile(SocialProvider.NAVER, "abc", "st", REDIRECT);

        server.verify();
        assertThat(profile).isEqualTo(new ProviderProfile("n1", "a@x.com", "네이버팬"));
    }

    @Test
    @DisplayName("구글: 토큰 교환(redirect_uri 포함) 후 userinfo 의 sub/email/name 을 매핑")
    void google_profile_mapping() {
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.containsString("redirect_uri=http%3A%2F%2Flocalhost%3A8080")))
                .andRespond(withSuccess(Fixtures.read("oauth/google-token.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openidconnect.googleapis.com/v1/userinfo"))
                .andExpect(header("Authorization", "Bearer ya29.a0AfH6SMBx-fixture-access-token"))
                .andRespond(withSuccess(Fixtures.read("oauth/google-userinfo-g1.json"), MediaType.APPLICATION_JSON));

        ProviderProfile profile = adapter.fetchProfile(SocialProvider.GOOGLE, "code", "st",
                "http://localhost:8080/api/v1/auth/google/callback");

        server.verify();
        assertThat(profile).isEqualTo(new ProviderProfile("g1", "a@x.com", "Google Fan"));
    }

    @Test
    @DisplayName("제공자 5xx 는 OAUTH_PROVIDER_ERROR")
    void provider_failure_becomes_gateway_error() {
        server.expect(requestTo("https://nid.naver.com/oauth2.0/token")).andRespond(withServerError());
        assertThatThrownBy(() -> adapter.fetchProfile(SocialProvider.NAVER, "abc", "st", REDIRECT))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode()).isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR);
    }

    @Test
    @DisplayName("인가 URL 에 client_id·redirect_uri·state 가 들어가고 구글은 scope 도 포함")
    void authorize_urls() {
        String naver = adapter.authorizeUrl(SocialProvider.NAVER, REDIRECT, "st1");
        assertThat(naver).startsWith("https://nid.naver.com/oauth2.0/authorize?")
                .contains("response_type=code").contains("client_id=naver-id").contains("state=st1")
                .contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fapi%2Fv1%2Fauth%2Fnaver%2Fcallback");
        String google = adapter.authorizeUrl(SocialProvider.GOOGLE, REDIRECT, "st2");
        assertThat(google).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("scope=openid%20email%20profile").contains("state=st2");
    }
}
