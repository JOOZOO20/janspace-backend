package com.stagelog.setlist.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.setlist.application.port.OAuthStateStore;
import com.stagelog.setlist.application.port.YouTubeOAuthPort;
import com.stagelog.setlist.domain.YouTubeCredential;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stagelog 유튜브 채널 연결 (관리자 1회): 인가 URL 발급(state 는 Redis) -> 콜백에서 코드를 리프레시 토큰으로 바꿔 DB 단일 행에 저장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class YouTubeAuthUseCase {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final YouTubeOAuthPort oauth;
    private final OAuthStateStore states;
    private final YouTubeCredentialRepository credentials;

    public String authorizeUrl() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        states.save(state);
        return oauth.authorizeUrl(state);
    }

    @Transactional
    public void handleCallback(String code, String state) {
        if (code == null || code.isBlank() || !states.consume(state)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "유튜브 OAuth state 가 유효하지 않습니다.");
        }
        // 외부 호출: 구글 토큰 교환
        String refreshToken = oauth.exchangeCode(code);
        credentials.findById(YouTubeCredential.SINGLETON_ID)
                .ifPresentOrElse(c -> c.replace(refreshToken), () -> credentials.save(new YouTubeCredential(refreshToken)));
        log.info("[유튜브 채널 연결] 리프레시 토큰을 저장했습니다");
    }
}
