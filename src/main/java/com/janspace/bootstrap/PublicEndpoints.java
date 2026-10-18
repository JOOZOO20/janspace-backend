package com.janspace.bootstrap;

import com.janspace.common.constant.ApiVersion;

/**
 * 인증 없이 열리는 경로. 제품 규칙 "보는 것은 자유" 에 따라 /api/v1 의 GET 은 공개, 쓰기는 로그인 필수다.
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
}
