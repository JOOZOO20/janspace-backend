package com.janspace.bootstrap;

import com.janspace.common.constant.ApiVersion;
import java.util.Arrays;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 인증 없이 열리는 경로. 제품 규칙 "보는 것은 자유" 에 따라 /api/v1 의 GET 은 공개, 쓰기는 로그인 필수다.
 * 예외로 GET 이라도 핸들러가 @CurrentUser(required=true) 를 받으면 로그인 필수다 (GET /me, GET /places 등).
 * 이 판정은 보안 필터가 아니라 CurrentUserArgumentResolver 의 401 이 하며, Swagger 자물쇠는 SwaggerConfig 가 같은 기준으로 붙인다.
 */
public final class PublicEndpoints {

    public static final String[] SWAGGER_PATTERNS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**"
    };

    // 메서드 무관 공개 경로. 유튜브 OAuth 콜백은 구글이 브라우저를 보내므로 Bearer 가 없다 (Redis 의 state 가 요청을 보호한다)
    public static final String[] PUBLIC_PATTERNS = {
            "/actuator/health",
            "/actuator/health/**",
            ApiVersion.V1_BASE + "/admin/youtube/callback"
    };

    // 관리자 권한(ADMIN)이 필요한 경로. 공개 GET 규칙보다 먼저 평가된다
    public static final String[] ADMIN_PATTERNS = {
            ApiVersion.V1_BASE + "/admin/**"
    };

    // 로그인 흐름 경로. GET 은 공개 GET 규칙으로, POST(token·refresh·logout) 는 이 규칙으로 열린다
    public static final String[] AUTH_PATTERNS = {
            ApiVersion.V1_BASE + "/auth/**"
    };

    // GET 만 공개되는 경로
    public static final String[] PUBLIC_READ_PATTERNS = {
            ApiVersion.V1_BASE + "/**"
    };

    private PublicEndpoints() {
    }

    // 토큰 없이 통과하는 (메서드, 경로)인가. SecurityConfig 의 authorizeHttpRequests 와 같은 순서로 판정한다 (Swagger 자물쇠 표시용)
    public static boolean isPublic(String method, String path) {
        if (matches(SWAGGER_PATTERNS, path) || matches(PUBLIC_PATTERNS, path)) {
            return true;
        }
        if (matches(ADMIN_PATTERNS, path)) {
            return false;
        }
        return ("GET".equals(method) && matches(PUBLIC_READ_PATTERNS, path))
                || ("POST".equals(method) && matches(AUTH_PATTERNS, path));
    }

    private static boolean matches(String[] patterns, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return Arrays.stream(patterns).anyMatch(p -> PathPatternParser.defaultInstance.parse(p).matches(container));
    }
}
