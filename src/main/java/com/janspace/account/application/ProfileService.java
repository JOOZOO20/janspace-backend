package com.janspace.account.application;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.account.application.port.RefreshTokenStore;
import com.janspace.account.domain.Departure;
import com.janspace.account.domain.User;
import com.janspace.account.domain.UserRepository;
import com.janspace.account.domain.UserSocialAccount;
import com.janspace.account.domain.UserSocialAccountRepository;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.UnauthorizedException;
import com.janspace.common.locale.SupportedLocale;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로필 조회·동의·수정·탈퇴. 다른 기능에는 AccountQuery 로만 보인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService implements AccountQuery {

    private final UserRepository userRepository;
    private final UserSocialAccountRepository socialAccountRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountSnapshot> find(Long userId) {
        return userRepository.findActiveById(userId).map(u -> new AccountSnapshot(
                u.getId(), u.getNickname(), u.getLocale(), u.isAdmin(), u.isConsented(),
                u.getDeparture() == null ? null : new AccountSnapshot.Departure(
                        u.getDeparture().getName(), u.getDeparture().getCountryCode(),
                        u.getDeparture().getLat().doubleValue(), u.getDeparture().getLng().doubleValue())));
    }

    @Transactional(readOnly = true)
    public UserProfile me(Long userId) {
        return profileOf(load(userId));
    }

    @Transactional
    public UserProfile consent(Long userId, String termsVersion) {
        User user = load(userId);
        user.consent(termsVersion, Instant.now());
        log.info("[약관 동의] userId={}, termsVersion={}", userId, termsVersion);
        return profileOf(user);
    }

    // null 인 필드는 바꾸지 않는다 (PATCH)
    @Transactional
    public UserProfile update(Long userId, String nickname, SupportedLocale locale, Departure departure) {
        User user = load(userId);
        if (nickname != null) {
            user.changeNickname(nickname);
        }
        if (locale != null) {
            user.changeLocale(locale);
        }
        if (departure != null) {
            user.changeDeparture(departure);
        }
        log.info("[프로필 수정] userId={}, nickname={}, locale={}, departure={}", userId, nickname != null,
                locale, departure != null);
        return profileOf(user);
    }

    // 소프트 삭제 + 소셜 행 삭제(재가입 허용) + 리프레시 가족 전부 폐기 + UserWithdrawn 발행
    @Transactional
    public void withdraw(Long userId) {
        User user = load(userId);
        user.withdraw(Instant.now());
        socialAccountRepository.deleteAllByUserId(userId);
        refreshTokenStore.revokeAllOf(userId);
        eventPublisher.publishEvent(new UserWithdrawn(userId));
        log.info("[탈퇴] userId={}", userId);
    }

    // 탈퇴한 사용자의 아직 유효한 액세스 토큰은 401 (쓰기 요청의 ConsentInterceptor 와 같은 판정)
    private User load(Long userId) {
        return userRepository.findActiveById(userId).orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));
    }

    private UserProfile profileOf(User user) {
        return new UserProfile(user, socialAccountRepository.findAllByUserId(user.getId()).stream()
                .map(UserSocialAccount::getProvider).toList());
    }
}
