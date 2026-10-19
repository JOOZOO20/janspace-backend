package com.stagelog.account.application.port;

import com.stagelog.account.domain.SocialProvider;

/**
 * 소셜 제공자 인가 URL 생성과 코드 교환 포트. 구현은 infrastructure 가 integration 클라이언트로 한다.
 */
public interface OAuthProviderPort {

    String authorizeUrl(SocialProvider provider, String redirectUri, String state);

    // 인가 코드 -> 액세스 토큰 -> 프로필. 제공자 오류는 OAUTH_PROVIDER_ERROR 예외
    ProviderProfile fetchProfile(SocialProvider provider, String code, String state, String redirectUri);

    record ProviderProfile(String providerUserId, String email, String nickname) {
    }
}
