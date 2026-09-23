package com.janspace.account.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.account.api.dto.TokenRequest;
import com.janspace.account.api.dto.TokenResponse;
import com.janspace.account.api.dto.UserResponse;
import com.janspace.account.application.IssuedTokens;
import com.janspace.account.application.SocialLoginService;
import com.janspace.account.application.SocialLoginService.CallbackResult;
import com.janspace.account.application.port.LoginMode;
import com.janspace.account.domain.SocialProvider;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.UnauthorizedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 소셜 로그인 흐름. authorize·callback 은 브라우저 리다이렉트, token·refresh·logout 은 JSON.
 */
@Slf4j
@Tag(name = "인증 (Auth)", description = "네이버·구글 소셜 로그인, PKCE 코드 교환, 토큰 갱신·로그아웃")
@RestController
@RequestMapping(V1_BASE + "/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";
    static final String COOKIE_PATH = V1_BASE + "/auth";

    private final SocialLoginService socialLoginService;
    private final String frontUrl;
    private final boolean cookieSecure;

    public AuthController(SocialLoginService socialLoginService,
                          @Value("${janspace.front-url}") String frontUrl,
                          @Value("${janspace.auth.cookie-secure}") boolean cookieSecure) {
        this.socialLoginService = socialLoginService;
        this.frontUrl = frontUrl;
        this.cookieSecure = cookieSecure;
    }

    @Operation(summary = "소셜 로그인 시작", description = "PKCE code_challenge 를 받아 state 를 만들고 제공자 인가 페이지로 302 리다이렉트합니다.")
    @GetMapping("/{provider}/authorize")
    public ResponseEntity<Void> authorize(
            @Parameter(description = "제공자 (naver, google)", example = "naver") @PathVariable String provider,
            @Parameter(description = "PKCE code_challenge (base64url, 43~128자)", example = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
            @RequestParam(name = "code_challenge", required = false) String codeChallenge,
            @Parameter(description = "PKCE 방식. S256 만 지원", example = "S256")
            @RequestParam(name = "code_challenge_method", required = false) String method,
            @Parameter(description = "목적. login 만 허용 (연결은 POST /me/providers/{provider})", example = "login")
            @RequestParam(name = "mode", defaultValue = "login") String mode) {
        SocialProvider socialProvider = SocialProvider.fromPath(provider);
        if (!"login".equalsIgnoreCase(mode)) {
            throw new ApplicationException(ErrorCode.INVALID_INPUT, "mode 는 login 만 허용됩니다.");
        }
        String url = socialLoginService.beginAuthorization(socialProvider, codeChallenge, method, LoginMode.LOGIN,
                null, redirectUri(socialProvider));
        return ResponseEntity.status(302).location(URI.create(url)).build();
    }

    @Operation(summary = "제공자 콜백", description = "state 를 검증·소비하고 일회용 코드를 발급해 프론트 콜백 페이지로 302 리다이렉트합니다. 실패 시 error 파라미터로 리다이렉트합니다.")
    @GetMapping("/{provider}/callback")
    public ResponseEntity<Void> callback(
            @Parameter(description = "제공자 (naver, google)", example = "naver") @PathVariable String provider,
            @Parameter(description = "제공자가 준 인가 코드", example = "4/0AX4XfWh...") @RequestParam(required = false) String code,
            @Parameter(description = "authorize 때 발급한 state", example = "Qm9ndXNTdGF0ZQ") @RequestParam(required = false) String state,
            @Parameter(description = "제공자가 준 오류 코드 (사용자 거부 등)", example = "access_denied")
            @RequestParam(required = false) String error) {
        SocialProvider socialProvider = SocialProvider.fromPath(provider);
        // 제공자가 오류를 돌려주면 코드 교환 없이 프론트로 넘긴다. 어느 페이지로 갈지 모르므로 로그인 콜백으로
        if (error != null || code == null || state == null) {
            log.warn("[소셜 콜백 오류] provider={}, error={}", socialProvider, error);
            return redirect(frontUrl + "/auth/callback", "error", ErrorCode.OAUTH_PROVIDER_ERROR.name());
        }
        try {
            CallbackResult result = socialLoginService.handleCallback(socialProvider, code, state, redirectUri(socialProvider));
            String page = result.mode() == LoginMode.LINK ? "/settings/link-callback" : "/auth/callback";
            return redirect(frontUrl + page, "code", result.code());
        } catch (ApplicationException e) {
            // 브라우저 리다이렉트 중이라 JSON 봉투 대신 error 파라미터로 전달한다
            log.warn("[소셜 콜백 실패] provider={}, code={}", socialProvider, e.getErrorCode());
            return redirect(frontUrl + "/auth/callback", "error", e.getErrorCode().name());
        }
    }

    @Operation(summary = "일회용 코드 교환", description = "일회용 코드와 PKCE verifier 를 검증해 액세스 토큰을 발급하고 리프레시 토큰을 HttpOnly 쿠키로 내립니다. 코드는 성공·실패와 무관하게 폐기됩니다.")
    @PostMapping("/token")
    public ResponseEntity<ResponseDTO<TokenResponse>> token(@RequestBody @Valid TokenRequest request) {
        IssuedTokens tokens = socialLoginService.exchange(request.code(), request.codeVerifier());
        return withRefreshCookie(tokens);
    }

    @Operation(summary = "토큰 갱신", description = "refresh_token 쿠키로 새 액세스 토큰과 회전된 리프레시 토큰을 발급합니다. 이미 회전된 토큰을 다시 쓰면 가족 전체가 폐기됩니다.")
    @PostMapping("/refresh")
    public ResponseEntity<ResponseDTO<TokenResponse>> refresh(
            @Parameter(description = "리프레시 토큰 쿠키", example = "refresh_token=...")
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return withRefreshCookie(socialLoginService.refresh(refreshToken));
    }

    @Operation(summary = "로그아웃", description = "리프레시 토큰 가족을 폐기하고 쿠키를 지웁니다. 액세스 토큰은 만료(최대 30분)까지 유효합니다.")
    @PostMapping("/logout")
    public ResponseEntity<ResponseDTO<Void>> logout(
            @Parameter(description = "리프레시 토큰 쿠키", example = "refresh_token=...")
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            socialLoginService.logout(refreshToken);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .body(ResponseDTO.ok());
    }

    private ResponseEntity<ResponseDTO<TokenResponse>> withRefreshCookie(IssuedTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(tokens.refreshToken(), tokens.refreshTtl()).toString())
                .body(ResponseDTO.okWithData(new TokenResponse(tokens.accessToken(), UserResponse.from(tokens.profile()))));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true).secure(cookieSecure).path(COOKIE_PATH).sameSite("Lax").maxAge(maxAge).build();
    }

    private static ResponseEntity<Void> redirect(String base, String param, String value) {
        URI uri = UriComponentsBuilder.fromUriString(base).queryParam(param, value).build().encode().toUri();
        return ResponseEntity.status(302).location(uri).build();
    }

    // 현재 요청의 호스트 기준 콜백 URL. forward-headers-strategy=framework 라 프록시 뒤에서도 공개 주소가 된다
    static String redirectUri(SocialProvider provider) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path(COOKIE_PATH + "/" + provider.path() + "/callback").build().toUriString();
    }
}
