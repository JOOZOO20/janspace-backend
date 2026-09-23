package com.janspace.account.application;

import com.janspace.account.application.port.AuthCodeStore;
import com.janspace.account.application.port.AuthCodeStore.LinkCode;
import com.janspace.account.application.port.LoginMode;
import com.janspace.account.domain.SocialProvider;
import com.janspace.account.domain.User;
import com.janspace.account.domain.UserRepository;
import com.janspace.account.domain.UserSocialAccount;
import com.janspace.account.domain.UserSocialAccountRepository;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.common.exception.predefined.UnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 연결: 시작(authorizeUrl) 과 완료(연결 코드 + PKCE 검증 후 부착). 콜백 자체는 SocialLoginService 가 처리한다.
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
        User user = userRepository.findActiveById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND));

        // 2. 소셜 계정이 다른 사용자 것이면 409, 같은 제공자가 이미 있으면 409
        socialAccountRepository.findByProviderAndProviderUserId(provider, link.providerUserId()).ifPresent(existing -> {
            throw new ConflictException(existing.getUser().getId().equals(userId)
                    ? ErrorCode.PROVIDER_ALREADY_LINKED : ErrorCode.SOCIAL_ACCOUNT_ALREADY_LINKED);
        });
        if (socialAccountRepository.existsByUserIdAndProvider(userId, provider)) {
            throw new ConflictException(ErrorCode.PROVIDER_ALREADY_LINKED);
        }

        // 3. 부착
        socialAccountRepository.save(new UserSocialAccount(user, provider, link.providerUserId(), link.email()));
        log.info("[계정 연결 완료] userId={}, provider={}", userId, provider);
    }
}
