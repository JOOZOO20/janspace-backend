package com.janspace.bootstrap;

import tools.jackson.databind.ObjectMapper;
import com.janspace.account.application.AccessTokenVerifier;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 보안 설정: 무상태, CSRF 끔, 공개 경로 허용, /admin 은 ADMIN 권한, 나머지는 401 봉투. JWT 필터가 Bearer 토큰으로 인증을 채운다.
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
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
