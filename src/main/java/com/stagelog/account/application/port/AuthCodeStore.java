package com.stagelog.account.application.port;

import com.stagelog.account.domain.SocialProvider;
import java.util.Optional;

/**
 * OAuth state·일회용 인증 코드·일회용 연결 코드 저장소 (Redis). consume 은 원자적으로 읽고 지운다 (1회 소비).
 */
public interface AuthCodeStore {

    // state -> {provider, challenge, mode, linkUserId?}, TTL 10분
    void saveState(String state, LoginState loginState);

    Optional<LoginState> consumeState(String state);

    // code -> {userId, challenge}, TTL 60초
    void saveAuthCode(String code, AuthCode authCode);

    Optional<AuthCode> consumeAuthCode(String code);

    // code -> {linkUserId, provider, providerUserId, email, challenge}, TTL 60초
    void saveLinkCode(String code, LinkCode linkCode);

    Optional<LinkCode> consumeLinkCode(String code);

    record LoginState(SocialProvider provider, String challenge, LoginMode mode, Long linkUserId) {
    }

    record AuthCode(Long userId, String challenge) {
    }

    record LinkCode(Long linkUserId, SocialProvider provider, String providerUserId, String email, String challenge) {
    }
}
