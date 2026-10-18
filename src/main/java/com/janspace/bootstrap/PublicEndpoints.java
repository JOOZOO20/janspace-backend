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

    // 메서드 무관 공개 경로
    public static final String[] PUBLIC_PATTERNS = {
            "/actuator/health",
            "/actuator/health/**"
    };

    // GET 만 공개되는 경로
    public static final String[] PUBLIC_READ_PATTERNS = {
            ApiVersion.V1_BASE + "/**"
    };

    private PublicEndpoints() {
    }
}
