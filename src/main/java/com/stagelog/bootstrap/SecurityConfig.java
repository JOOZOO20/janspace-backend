package com.stagelog.bootstrap;

import tools.jackson.databind.ObjectMapper;
import com.stagelog.account.application.AccessTokenVerifier;
import com.stagelog.account.application.ServiceAccountAuthenticator;
import com.stagelog.account.domain.ServiceAccountRole;
import com.stagelog.common.constant.ApiVersion;
import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 보안 설정: 무상태, CSRF 끔, 프론트 출처만 CORS 허용, 공개 경로 허용, /admin 은 ADMIN 권한, 나머지는 401 봉투.
 * JWT 필터가 Bearer 토큰으로 인증을 채운다. 공개 판정 순서를 바꾸면 PublicEndpoints.isPublic 도 같이 고친다.
 * X-Api-Key 헤더가 있는 요청은 서비스 계정 체인이 먼저 받아 MCP 역할의 허용 경로만 열고 나머지는 모두 막는다(403).
 */
@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    public static final String ADMIN_AUTHORITY = "ADMIN";

    // MCP_READER 허용 경로 (GET 만). MCP 진단 Tool 이 부르는 관리자 조회 API 와 정확히 같아야 하며, Tool 을 더할 때 함께 연다
    static final String[] MCP_READER_PATTERNS = {
            ApiVersion.V1_BASE + "/admin/sync-logs",
            ApiVersion.V1_BASE + "/admin/songs/unmatched",
            ApiVersion.V1_BASE + "/admin/songs/search",
            ApiVersion.V1_BASE + "/admin/events/{id}/sync-status",
            ApiVersion.V1_BASE + "/admin/events/{id}/source-comparison"
    };
    // MCP_REQUESTER 허용 경로 (POST 만). 조치는 실행하지 않고 승인 대기 요청만 만든다
    static final String AGENT_REQUEST_CREATE = ApiVersion.V1_BASE + "/admin/agent-requests";

    private final ObjectMapper objectMapper;
    private final AccessTokenVerifier accessTokenVerifier;
    private final ServiceAccountAuthenticator serviceAccountAuthenticator;

    // ADMIN 권한은 어떤 경로에서도 주지 않는다. jan.mcp.service-account.enabled=false 면 체인이 없어 X-Api-Key 요청도 아래 JWT 체인에서 401 이다
    @Bean
    @Order(1)
    @ConditionalOnProperty(name = "jan.mcp.service-account.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain serviceAccountFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(request -> request.getHeader(ServiceAccountAuthenticationFilter.HEADER) != null)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, MCP_READER_PATTERNS).hasAuthority(ServiceAccountRole.MCP_READER.name())
                        .requestMatchers(HttpMethod.POST, AGENT_REQUEST_CREATE).hasAuthority(ServiceAccountRole.MCP_REQUESTER.name())
                        .anyRequest().denyAll())
                .addFilterBefore(new ServiceAccountAuthenticationFilter(serviceAccountAuthenticator),
                        UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(forbiddenHandler()));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${stagelog.front-url}") String frontUrl)
            throws Exception {
        http
                // CorsFilter 가 인가보다 앞에서 preflight 에 바로 응답하므로 OPTIONS 는 토큰 없이 통과한다
                .cors(cors -> cors.configurationSource(corsSource(frontUrl)))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PublicEndpoints.SWAGGER_PATTERNS).permitAll()
                        .requestMatchers(PublicEndpoints.PUBLIC_PATTERNS).permitAll()
                        // 조치 요청은 MCP 서비스 계정만 만든다. 관리자는 목록·승인·거절만 한다
                        .requestMatchers(HttpMethod.POST, AGENT_REQUEST_CREATE).denyAll()
                        .requestMatchers(PublicEndpoints.ADMIN_PATTERNS).hasAuthority(ADMIN_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, PublicEndpoints.PUBLIC_READ_PATTERNS).permitAll()
                        .requestMatchers(HttpMethod.POST, PublicEndpoints.AUTH_PATTERNS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(accessTokenVerifier), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorizedEntryPoint())
                        .accessDeniedHandler(forbiddenHandler()));
        return http.build();
    }

    // 프론트 출처 하나만 정확히 일치로 허용한다. 리프레시 쿠키를 실어야 하므로 credentials 를 켜며, 그래서 와일드카드 출처는 쓸 수 없다
    static CorsConfigurationSource corsSource(String frontUrl) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, RequestIdFilter.HEADER));
        config.setExposedHeaders(List.of(RequestIdFilter.HEADER));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // 인증 없는 보호 자원 접근 -> 401 봉투
    private AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, exception) -> {
            log.warn("[인증 실패] method={}, uri={}", request.getMethod(), request.getRequestURI());
            writeEnvelope(response, ErrorCode.UNAUTHORIZED);
        };
    }

    // 인증됐지만 권한 부족 -> 403 봉투 (비관리자의 /admin 접근, MCP 역할의 허용 경로 밖 접근)
    private AccessDeniedHandler forbiddenHandler() {
        return (request, response, exception) -> {
            log.warn("[권한 부족] method={}, uri={}", request.getMethod(), request.getRequestURI());
            writeEnvelope(response, ErrorCode.FORBIDDEN);
        };
    }

    private void writeEnvelope(jakarta.servlet.http.HttpServletResponse response, ErrorCode code)
            throws java.io.IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ResponseDTO.error(code)));
    }
}
