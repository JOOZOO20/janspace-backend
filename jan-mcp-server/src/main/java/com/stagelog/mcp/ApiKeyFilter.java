package com.stagelog.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCP 엔드포인트 인증. Authorization: Bearer <client key> 의 SHA-256 으로 클라이언트를 찾고, 없으면 401 이다.
 * 찾은 클라이언트 ID 는 요청 속성으로 넘겨 Tool 호출 로그·rate limit 의 단위가 된다 (McpServerConfig 의 contextExtractor).
 */
@Slf4j
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String CLIENT_ATTRIBUTE = "jan.mcp.client";
    private static final String BEARER = "Bearer ";

    private final Map<String, String> clientsByKeyHash;

    public ApiKeyFilter(Map<String, String> clientsByKeyHash) {
        this.clientsByKeyHash = clientsByKeyHash;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String client = header != null && header.startsWith(BEARER)
                ? clientsByKeyHash.get(sha256Hex(header.substring(BEARER.length()).trim())) : null;
        if (client == null) {
            log.warn("[MCP 인증 실패] method={}, uri={}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"unauthorized\"}");
            return;
        }
        request.setAttribute(CLIENT_ATTRIBUTE, client);
        chain.doFilter(request, response);
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없는 JVM 입니다", e);
        }
    }
}
