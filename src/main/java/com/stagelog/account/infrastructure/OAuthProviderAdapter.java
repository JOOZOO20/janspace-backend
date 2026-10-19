package com.stagelog.account.infrastructure;

import com.stagelog.account.application.port.OAuthProviderPort;
import com.stagelog.account.domain.SocialProvider;
import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.integration.google.GoogleOAuthClient;
import com.stagelog.integration.google.GoogleTokenResponse;
import com.stagelog.integration.google.GoogleUserInfoResponse;
import com.stagelog.integration.naver.NaverOAuthClient;
import com.stagelog.integration.naver.NaverProfileResponse;
import com.stagelog.integration.naver.NaverTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * 제공자별 클라이언트를 OAuthProviderPort 로 묶고 외부 응답을 ProviderProfile 로 바꾼다.
 * HTTP 오류·비정상 응답은 모두 502 OAUTH_PROVIDER_ERROR 다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuthProviderAdapter implements OAuthProviderPort {

    private final NaverOAuthClient naver;
    private final GoogleOAuthClient google;

    @Override
    public String authorizeUrl(SocialProvider provider, String redirectUri, String state) {
        return switch (provider) {
            case NAVER -> naver.authorizeUrl(redirectUri, state);
            case GOOGLE -> google.authorizeUrl(redirectUri, state);
        };
    }

    @Override
    public ProviderProfile fetchProfile(SocialProvider provider, String code, String state, String redirectUri) {
        try {
            return switch (provider) {
                case NAVER -> fromNaver(code, state);
                case GOOGLE -> fromGoogle(code, redirectUri);
            };
        } catch (RestClientException e) {
            // 한 줄 맥락만 남기고 핸들러가 502 를 기록한다
            log.warn("[소셜 제공자 호출 실패] provider={}, reason={}", provider, e.getMessage());
            throw new ApplicationException(ErrorCode.OAUTH_PROVIDER_ERROR, e);
        }
    }

    private ProviderProfile fromNaver(String code, String state) {
        NaverTokenResponse token = naver.exchangeCode(code, state);
        if (token == null || token.accessToken() == null) {
            throw providerError("naver token", token == null ? null : token.error());
        }
        NaverProfileResponse profile = naver.fetchProfile(token.accessToken());
        if (profile == null || profile.response() == null || profile.response().id() == null) {
            throw providerError("naver profile", profile == null ? null : profile.resultCode());
        }
        NaverProfileResponse.Profile p = profile.response();
        return new ProviderProfile(p.id(), p.email(), p.nickname());
    }

    private ProviderProfile fromGoogle(String code, String redirectUri) {
        GoogleTokenResponse token = google.exchangeCode(code, redirectUri);
        if (token == null || token.accessToken() == null) {
            throw providerError("google token", null);
        }
        GoogleUserInfoResponse info = google.fetchUserInfo(token.accessToken());
        if (info == null || info.sub() == null) {
            throw providerError("google userinfo", null);
        }
        return new ProviderProfile(info.sub(), info.email(), info.name());
    }

    private ApplicationException providerError(String step, String detail) {
        log.warn("[소셜 제공자 응답 비정상] step={}, detail={}", step, detail);
        return new ApplicationException(ErrorCode.OAUTH_PROVIDER_ERROR);
    }
}
