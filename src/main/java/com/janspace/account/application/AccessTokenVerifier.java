package com.janspace.account.application;

import com.janspace.common.auth.AuthenticatedUser;
import java.util.Optional;

/**
 * 액세스 토큰 검증 계약. bootstrap 의 JwtAuthenticationFilter 가 호출한다. 만료·위조는 예외가 아니라 empty 다.
 */
public interface AccessTokenVerifier {

    Optional<AuthenticatedUser> verify(String token);
}
