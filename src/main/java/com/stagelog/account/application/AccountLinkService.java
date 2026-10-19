package com.stagelog.account.application;

import com.stagelog.account.application.port.AuthCodeStore;
import com.stagelog.account.application.port.AuthCodeStore.LinkCode;
import com.stagelog.account.application.port.LoginMode;
import com.stagelog.account.domain.SocialProvider;
import com.stagelog.account.domain.User;
import com.stagelog.account.domain.UserRepository;
import com.stagelog.account.domain.UserSocialAccount;
import com.stagelog.account.domain.UserSocialAccountRepository;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.common.exception.predefined.UnauthorizedException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 연결: 시작(authorizeUrl), 완료(연결 코드 + PKCE 검증 후 부착), 해제. 콜백 자체는 SocialLoginService 가 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLinkService {

    private final SocialLoginService socialLoginService;
    private final AuthCodeStore authCodeStore;
    private final UserRepository userRepository;
    private final UserSocialAccountRepository socialAccountRepository;

    public String start(Long userId, SocialProvider provider, String codeChallenge, String redirectUri) {
        // 같은 제공자가 이미 연결돼 있으면 제공자로 보내기 전에 거절한다
        if (socialAccountRepository.existsByUserIdAndProvider(userId, provider)) {
            throw new ConflictException(ErrorCode.PROVIDER_ALREADY_LINKED);
        }
        return socialLoginService.beginAuthorization(provider, codeChallenge, "S256", LoginMode.LINK, userId, redirectUri);
    }

    @Transactional
    public void complete(Long userId, SocialProvider provider, String code, String codeVerifier) {
        // 1. 연결 코드 1회 소비 + PKCE + 소유자 확인. 어느 하나라도 틀리면 401 이고 코드는 이미 폐기됐다
        LinkCode link = authCodeStore.consumeLinkCode(code)
                .filter(l -> l.provider() == provider)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_AUTH_CODE));
        if (!Pkce.matches(codeVerifier, link.challenge()) || !userId.equals(link.linkUserId())) {
            log.warn("[검증 실패 - 계정 연결 코드] userId={}, provider={}", userId, provider);
            throw new UnauthorizedException(ErrorCode.INVALID_AUTH_CODE);
        }
        // 2. 사용자 행 잠금: 같은 사용자의 동시 완료가 둘 다 "같은 제공자 없음"으로 통과하지 않게 한다 (unlink 와 같은 잠금).
        //    DB 의 UNIQUE (user_id, provider) 가 최후 방어선이다. 탈퇴한 사용자는 ProfileService·ConsentInterceptor 와 같이 401
        User user = userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));

        // 3. 소셜 계정이 다른 사용자 것이면 409, 같은 제공자가 이미 있으면 409
        socialAccountRepository.findByProviderAndProviderUserId(provider, link.providerUserId()).ifPresent(existing -> {
            throw new ConflictException(existing.getUser().getId().equals(userId)
                    ? ErrorCode.PROVIDER_ALREADY_LINKED : ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED);
        });
        if (socialAccountRepository.existsByUserIdAndProvider(userId, provider)) {
            throw new ConflictException(ErrorCode.PROVIDER_ALREADY_LINKED);
        }

        // 4. 부착
        socialAccountRepository.save(new UserSocialAccount(user, provider, link.providerUserId(), link.email()));
        log.info("[계정 연결 완료] userId={}, provider={}", userId, provider);
    }

    // 해제. 로그인 수단은 소셜 계정뿐이라 마지막 하나를 지우면 다시 들어올 방법이 없으므로 거부한다
    @Transactional
    public void unlink(Long userId, SocialProvider provider) {
        // 1. 사용자 행 잠금: 서로 다른 제공자의 동시 해제가 둘 다 "남은 수단 있음"으로 통과하지 않게 한다
        userRepository.findActiveByIdForUpdate(userId)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));

        // 2. 연결돼 있지 않으면 404, 마지막 수단이면 409
        List<UserSocialAccount> accounts = socialAccountRepository.findAllByUserId(userId);
        UserSocialAccount target = accounts.stream().filter(a -> a.getProvider() == provider).findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.PROVIDER_NOT_LINKED));
        if (accounts.size() == 1) {
            log.warn("[검증 실패 - 마지막 로그인 수단 해제] userId={}, provider={}", userId, provider);
            throw new ConflictException(ErrorCode.CANNOT_UNLINK_LAST_PROVIDER);
        }

        // 3. 삭제. 이 소셜 계정으로 다시 로그인하면 새 사용자로 가입된다
        socialAccountRepository.delete(target);
        log.info("[계정 연결 해제] userId={}, provider={}, remaining={}", userId, provider, accounts.size() - 1);
    }
}
