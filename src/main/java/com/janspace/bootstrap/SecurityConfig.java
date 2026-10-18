package com.janspace.bootstrap;

import tools.jackson.databind.ObjectMapper;
import com.janspace.account.application.AccessTokenVerifier;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 */
@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    public static final String ADMIN_AUTHORITY = "ADMIN";

    private final ObjectMapper objectMapper;
    private final AccessTokenVerifier accessTokenVerifier;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${janspace.front-url}") String frontUrl)
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

    // 인증됐지만 권한 부족 -> 403 봉투 (비관리자의 /admin 접근)
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
