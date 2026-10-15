package com.janspace.bootstrap;

import com.janspace.account.application.AccountQuery;
import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.common.constant.ApiVersion;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ForbiddenException;
import com.janspace.common.exception.predefined.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 동의 게이트. /api/v1 의 쓰기 요청은 약관 동의(consented_at) 후에만 통과하고, 동의 전이면 403 CONSENT_REQUIRED 다.
 * 예외: /api/v1/auth/**, POST /api/v1/me/consent, DELETE /api/v1/me(탈퇴). 비로그인 요청은 보안 필터가 먼저 401 을 낸다.
 */
@RequiredArgsConstructor
public class ConsentInterceptor implements HandlerInterceptor {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String AUTH_PREFIX = ApiVersion.V1_BASE + "/auth/";
    private static final String CONSENT_PATH = ApiVersion.V1_BASE + "/me/consent";
    private static final String ME_PATH = ApiVersion.V1_BASE + "/me";

    private final AccountQuery accountQuery;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!applies(request)) {
            return true;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return true;
        }
        // 탈퇴한 사용자의 유효한 액세스 토큰도 여기서 걸러진다
        boolean consented = accountQuery.find(user.userId())
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHORIZED))
                .consented();
        if (!consented) {
            throw new ForbiddenException(ErrorCode.CONSENT_REQUIRED);
        }
        return true;
    }

    private static boolean applies(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (READ_METHODS.contains(request.getMethod()) || !uri.startsWith(ApiVersion.V1_BASE + "/")) {
            return false;
        }
        if (uri.startsWith(AUTH_PREFIX)) {
            return false;
        }
        // 동의 자체와 탈퇴는 동의 전에도 열어 둔다. 동의하지 않은 사용자도 계정을 지울 수 있어야 한다
        String method = request.getMethod();
        return !(("POST".equals(method) && uri.equals(CONSENT_PATH)) || ("DELETE".equals(method) && uri.equals(ME_PATH)));
    }
}
