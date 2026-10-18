package com.janspace.account.application;

import java.time.Duration;

/**
 * 코드 교환·갱신 결과. refreshToken 은 컨트롤러가 HttpOnly 쿠키로만 내려보낸다.
 */
public record IssuedTokens(String accessToken, String refreshToken, Duration refreshTtl, UserProfile profile) {
}
