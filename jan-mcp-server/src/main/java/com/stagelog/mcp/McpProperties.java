package com.stagelog.mcp;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * jan.mcp 설정. mainApiKey 는 메인 앱 service_accounts 의 key 원문, clientKeys 는 "클라이언트ID:key의SHA-256hex" 를 쉼표로 이은 값이다.
 * MCP 클라이언트 key 원문은 설정에 두지 않는다.
 */
@ConfigurationProperties("jan.mcp")
public record McpProperties(String mainBaseUrl, String mainApiKey, String clientKeys, int rateLimitPerMinute) {

    // key 해시 -> 클라이언트 ID
    public Map<String, String> clientsByKeyHash() {
        if (clientKeys == null || clientKeys.isBlank()) {
            return Map.of();
        }
        return Arrays.stream(clientKeys.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .map(entry -> {
                    int colon = entry.indexOf(':');
                    if (colon <= 0 || colon == entry.length() - 1) {
                        throw new IllegalStateException("jan.mcp.client-keys 형식은 clientId:sha256hex 입니다");
                    }
                    return Map.entry(entry.substring(colon + 1).toLowerCase(), entry.substring(0, colon));
                })
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
