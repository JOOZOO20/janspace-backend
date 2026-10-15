package com.janspace.account.application;

import com.janspace.account.application.port.AuthCodeStore;
import com.janspace.account.application.port.AuthCodeStore.AuthCode;
import com.janspace.account.application.port.AuthCodeStore.LinkCode;
import com.janspace.account.application.port.AuthCodeStore.LoginState;
import com.janspace.account.application.port.AvatarStoragePort;
import com.janspace.account.application.port.LoginMode;
import com.janspace.account.application.port.OAuthProviderPort;
import com.janspace.account.application.port.OAuthProviderPort.ProviderProfile;
import com.janspace.account.application.port.RefreshTokenStore;
import com.janspace.account.application.port.RefreshTokenStore.Entry;
import com.janspace.account.domain.SocialProvider;
import com.janspace.account.domain.User;
import com.janspace.account.domain.UserRepository;
import com.janspace.account.domain.UserSocialAccount;
import com.janspace.account.domain.UserSocialAccountRepository;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.exception.predefined.UnauthorizedException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 로그인 흐름: authorize -> 제공자 콜백 -> 일회용 코드 -> PKCE 교환 -> 액세스/리프레시 발급, 갱신·로그아웃.
 */
@Slf4j
@Service
public class SocialLoginService {

    private static final String DEFAULT_NICKNAME = "잔나비 팬";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthCodeStore authCodeStore;
    private final RefreshTokenStore refreshTokenStore;
    private final OAuthProviderPort oauthProvider;
    private final AccessTokenService accessTokenService;
    private final UserRepository userRepository;
    private final UserSocialAccountRepository socialAccountRepository;
    private final AvatarStoragePort avatars;
    private final Duration refreshTtl;

    public SocialLoginService(AuthCodeStore authCodeStore, RefreshTokenStore refreshTokenStore,
                              OAuthProviderPort oauthProvider, AccessTokenService accessTokenService,
                              UserRepository userRepository, UserSocialAccountRepository socialAccountRepository,
                              AvatarStoragePort avatars, @Value("${janspace.auth.refresh-ttl}") Duration refreshTtl) {
        this.authCodeStore = authCodeStore;
        this.refreshTokenStore = refreshTokenStore;
        this.oauthProvider = oauthProvider;
        this.accessTokenService = accessTokenService;
        this.userRepository = userRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.avatars = avatars;
        this.refreshTtl = refreshTtl;
    }

    // state 를 만들어 저장하고 제공자 인가 URL 을 돌려준다. LINK 모드는 AccountLinkService 가 linkUserId 를 채워 호출한다
    public String beginAuthorization(SocialProvider provider, String codeChallenge, String method, LoginMode mode,
                                     Long linkUserId, String redirectUri) {
        if (!"S256".equals(method) || !Pkce.isValidChallenge(codeChallenge)) {
            throw new BadRequestException(ErrorCode.INVALID_PKCE);
        }
        String state = randomToken();
        authCodeStore.saveState(state, new LoginState(provider, codeChallenge, mode, linkUserId));
        log.info("[소셜 로그인 시작] provider={}, mode={}, linkUserId={}", provider, mode, linkUserId);
        return oauthProvider.authorizeUrl(provider, redirectUri, state);
    }

    /**
     * 제공자 콜백. state 를 1회 소비하고 프로필을 받은 뒤 모드에 따라 로그인 코드 또는 연결 코드를 발급한다.
     * 반환값은 컨트롤러가 프론트로 리다이렉트할 때 붙일 코드다.
     */
    @Transactional
    public CallbackResult handleCallback(SocialProvider provider, String code, String state, String redirectUri) {
        // 1. state 검증·소비 (다른 제공자의 state 는 거부)
        LoginState loginState = authCodeStore.consumeState(state)
                .filter(s -> s.provider() == provider)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_AUTH_CODE));

        // 2. 코드 교환 + 프로필 조회 (제공자 오류는 포트가 OAUTH_PROVIDER_ERROR 로 던진다)
        ProviderProfile profile = oauthProvider.fetchProfile(provider, code, state, redirectUri);
        String oneTimeCode = randomToken();

        // 3-A. LINK 모드: 부착하지 않고 연결 코드만 발급한다
        if (loginState.mode() == LoginMode.LINK) {
            authCodeStore.saveLinkCode(oneTimeCode, new LinkCode(loginState.linkUserId(), provider,
                    profile.providerUserId(), profile.email(), loginState.challenge()));
            log.info("[계정 연결 콜백] provider={}, linkUserId={}", provider, loginState.linkUserId());
            return new CallbackResult(LoginMode.LINK, oneTimeCode);
        }

        // 3-B. LOGIN 모드: 소셜 계정으로 사용자 조회, 없으면 새 사용자 (이메일로 합치지 않는다)
        User user = socialAccountRepository.findByProviderAndProviderUserId(provider, profile.providerUserId())
                .map(UserSocialAccount::getUser)
                .filter(u -> !u.isDeleted())
                .orElseGet(() -> register(provider, profile));
        authCodeStore.saveAuthCode(oneTimeCode, new AuthCode(user.getId(), loginState.challenge()));
        log.info("[소셜 로그인 콜백] provider={}, userId={}", provider, user.getId());
        return new CallbackResult(LoginMode.LOGIN, oneTimeCode);
    }

    private User register(SocialProvider provider, ProviderProfile profile) {
        String nickname = profile.nickname() == null || profile.nickname().isBlank() ? DEFAULT_NICKNAME : profile.nickname();
        User user = userRepository.save(new User(nickname, profile.email()));
        socialAccountRepository.save(new UserSocialAccount(user, provider, profile.providerUserId(), profile.email()));
        log.info("[가입] provider={}, userId={}", provider, user.getId());
        return user;
    }

    // 일회용 코드 + PKCE verifier -> 토큰. 코드는 검증 결과와 무관하게 소비된다
    @Transactional(readOnly = true)
    public IssuedTokens exchange(String code, String codeVerifier) {
        AuthCode authCode = authCodeStore.consumeAuthCode(code)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_AUTH_CODE));
        if (!Pkce.matches(codeVerifier, authCode.challenge())) {
            log.warn("[검증 실패 - PKCE 불일치] userId={}", authCode.userId());
            throw new UnauthorizedException(ErrorCode.INVALID_AUTH_CODE);
        }
        User user = userRepository.findActiveById(authCode.userId())
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_AUTH_CODE));
        return issue(user, UUID.randomUUID().toString());
    }

    // 리프레시 회전. 이미 회전된 토큰의 재사용은 탈취 신호로 보고 가족 전체를 폐기한다
    @Transactional(readOnly = true)
    public IssuedTokens refresh(String refreshToken) {
        Entry entry = refreshTokenStore.find(refreshToken)
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (entry.used()) {
            log.warn("[리프레시 재사용 감지 - 가족 폐기] userId={}, familyId={}", entry.userId(), entry.familyId());
            refreshTokenStore.revokeFamily(entry.familyId());
            throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (!entry.expiresAt().isAfter(Instant.now())) {
            throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        User user = userRepository.findActiveById(entry.userId())
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN));
        // 한계: find->markUsed 가 원자적이지 않아 동시 갱신 두 건이 모두 통과할 수 있다. 문제되면 Lua 스크립트로 묶는다
        refreshTokenStore.markUsed(refreshToken, entry);
        return issue(user, entry.familyId());
    }

    public void logout(String refreshToken) {
        Optional<Entry> entry = refreshTokenStore.find(refreshToken);
        entry.ifPresent(e -> {
            refreshTokenStore.revokeFamily(e.familyId());
            log.info("[로그아웃] userId={}, familyId={}", e.userId(), e.familyId());
        });
    }

    private IssuedTokens issue(User user, String familyId) {
        String refreshToken = randomToken();
        Instant expiresAt = Instant.now().plus(refreshTtl);
        refreshTokenStore.save(refreshToken, new Entry(user.getId(), familyId, expiresAt, false));
        String accessToken = accessTokenService.issue(user.getId(), user.isAdmin());
        return new IssuedTokens(accessToken, refreshToken, refreshTtl, UserProfile.of(user, socialAccountRepository, avatars));
    }

    // 32바이트 랜덤 -> base64url (43자)
    static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record CallbackResult(LoginMode mode, String code) {
    }
}
