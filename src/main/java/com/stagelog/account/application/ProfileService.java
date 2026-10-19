package com.stagelog.account.application;

import com.stagelog.account.application.event.UserWithdrawn;
import com.stagelog.account.application.port.AvatarStoragePort;
import com.stagelog.account.application.port.RefreshTokenStore;
import com.stagelog.account.domain.AvatarImageValidator;
import com.stagelog.account.domain.Departure;
import com.stagelog.account.domain.User;
import com.stagelog.account.domain.UserRepository;
import com.stagelog.account.domain.UserSocialAccountRepository;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.UnauthorizedException;
import com.stagelog.common.locale.SupportedLocale;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 프로필 조회·동의·수정·프로필 사진·탈퇴. 다른 기능에는 AccountQuery 로만 보인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService implements AccountQuery {

    private final UserRepository userRepository;
    private final UserSocialAccountRepository socialAccountRepository;
    private final RefreshTokenStore refreshTokenStore;
    private final AvatarStoragePort avatars;
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

    // PATCH. 인자가 null 이면 유지한다. locale·departure 가 Optional.empty 면 비운다(기기 언어로 되돌림·출발지 지움)
    @Transactional
    public UserProfile update(Long userId, String nickname, Optional<SupportedLocale> locale, Optional<Departure> departure) {
        User user = load(userId);
        if (nickname != null) {
            user.changeNickname(nickname);
        }
        if (locale != null) {
            user.changeLocale(locale.orElse(null));
        }
        if (departure != null) {
            user.changeDeparture(departure.orElse(null));
        }
        log.info("[프로필 수정] userId={}, nickname={}, locale={}, departure={}", userId, nickname != null,
                locale, departure == null ? "유지" : departure.isPresent() ? "변경" : "삭제");
        return profileOf(user);
    }

    // 프로필 사진 교체. 1) 검증 2) 행 잠금 3) 새 키로 업로드 4) 키 교체, 커밋되면 이전 객체·롤백되면 새 객체 삭제
    @Transactional
    public UserProfile changeAvatar(Long userId, byte[] image) {
        // 1. 형식·크기. 업로드 전에 거부한다
        AvatarImageValidator.Format format = AvatarImageValidator.validate(image);

        // 2. 같은 사용자의 동시 교체를 직렬화해 이전 키를 놓치지 않는다. 탈퇴한 사용자는 업로드 전에 401
        User user = loadForUpdate(userId);

        // 3. 외부 호출: S3 업로드. 키마다 UUID 라 CDN 캐시가 옛 사진을 보여 주지 않는다
        String key = "avatars/" + userId + "/" + UUID.randomUUID() + "." + format.extension();
        avatars.put(key, image, format.contentType());

        // 4. 교체
        String previous = user.getAvatarKey();
        user.changeAvatarKey(key);
        deleteObjectsAfterCompletion(previous, key);
        log.info("[프로필 사진 교체] userId={}, key={}, previousKey={}", userId, key, previous);
        return profileOf(user);
    }

    // 프로필 사진 삭제. 사진이 없어도 성공(멱등)이며 클라이언트는 기본 그림으로 돌아간다
    @Transactional
    public UserProfile deleteAvatar(Long userId) {
        User user = loadForUpdate(userId);
        String previous = user.getAvatarKey();
        if (previous != null) {
            user.changeAvatarKey(null);
            deleteObjectsAfterCompletion(previous, null);
            log.info("[프로필 사진 삭제] userId={}, key={}", userId, previous);
        }
        return profileOf(user);
    }

    // 소프트 삭제(프로필 사진 키 제거 포함) + 소셜 행 삭제(재가입 허용) + 리프레시 가족 전부 폐기 + UserWithdrawn 발행.
    // 프로필 사진 객체는 커밋 후 지운다
    @Transactional
    public void withdraw(Long userId) {
        // 진행 중인 사진 교체와 직렬화해야 막 올라간 사진 키를 놓치지 않는다
        User user = loadForUpdate(userId);
        String avatarKey = user.getAvatarKey();
        user.withdraw(Instant.now());
        socialAccountRepository.deleteAllByUserId(userId);
        refreshTokenStore.revokeAllOf(userId);
        eventPublisher.publishEvent(new UserWithdrawn(userId));
        deleteObjectsAfterCompletion(avatarKey, null);
        log.info("[탈퇴] userId={}, avatarKey={}", userId, avatarKey);
    }

    // 탈퇴한 사용자의 아직 유효한 액세스 토큰은 401 (쓰기 요청의 ConsentInterceptor 와 같은 판정)
    private User load(Long userId) {
        return userRepository.findActiveById(userId).orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));
    }

    private User loadForUpdate(Long userId) {
        return userRepository.findActiveByIdForUpdate(userId).orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED));
    }

    private UserProfile profileOf(User user) {
        return UserProfile.of(user, socialAccountRepository, avatars);
    }

    // 객체 삭제는 DB 결과가 확정된 뒤에만 한다. 커밋 전에 지우면 롤백 시 행이 지워진 객체를 가리키게 된다
    private void deleteObjectsAfterCompletion(String onCommit, String onRollback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // 결과를 모르는 경우(STATUS_UNKNOWN)는 어느 쪽도 지우지 않는다
                if (status == STATUS_COMMITTED) {
                    deleteQuietly(onCommit);
                } else if (status == STATUS_ROLLED_BACK) {
                    deleteQuietly(onRollback);
                }
            }
        });
    }

    // 정리 실패는 요청을 실패시키지 않는다. 남은 객체는 어떤 행도 가리키지 않는다
    private void deleteQuietly(String key) {
        if (key == null) {
            return;
        }
        try {
            avatars.delete(key);
            log.info("[프로필 사진 객체 삭제] key={}", key);
        } catch (RuntimeException e) {
            log.warn("[프로필 사진 객체 삭제 실패] key={}", key, e);
        }
    }
}
