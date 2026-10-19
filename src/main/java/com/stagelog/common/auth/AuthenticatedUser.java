package com.stagelog.common.auth;

/**
 * 인증된 요청의 주체. SecurityContext principal 로 들어가며 @CurrentUser 로 컨트롤러에 주입된다.
 * 토큰 파싱은 account 기능이 맡는다.
 */
public record AuthenticatedUser(Long userId, boolean admin) {
}
