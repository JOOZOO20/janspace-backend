package com.stagelog.bootstrap;

import com.stagelog.account.application.ServiceAccountAuthenticator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * X-Api-Key 헤더를 서비스 계정으로 검증해 SecurityContext 를 채운다. principal 은 계정 이름(String)이라
 * @CurrentUser(AuthenticatedUser) 를 받는 사용자 API 에는 쓸 수 없다. 실패해도 예외 없이 비인증으로 두고 엔트리 포인트가 401 을 쓴다.
 */
@RequiredArgsConstructor
public class ServiceAccountAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";

    private final ServiceAccountAuthenticator authenticator;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        authenticator.authenticate(request.getHeader(HEADER)).ifPresent(account -> SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(account.name(), null,
                        account.authorities().stream().map(SimpleGrantedAuthority::new).toList())));
        chain.doFilter(request, response);
    }
}
