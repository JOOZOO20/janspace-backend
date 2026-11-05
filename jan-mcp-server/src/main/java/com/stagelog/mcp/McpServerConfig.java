package com.stagelog.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP 서버 조립: /mcp 앞의 API key 필터, 클라이언트 ID 를 MCP transport context 로 옮기는 Streamable HTTP transport,
 * 메인 앱 호출용 RestClient. transport 는 자동 설정 빈(@ConditionalOnMissingBean)과 같게 만들고 contextExtractor 만 더한다.
 */
@Configuration
public class McpServerConfig {

    public static final String CLIENT_CONTEXT_KEY = "client";

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(McpProperties properties, McpServerStreamableHttpProperties http) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(new ApiKeyFilter(properties.clientsByKeyHash()));
        registration.addUrlPatterns(http.getMcpEndpoint(), http.getMcpEndpoint() + "/*");
        return registration;
    }

    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties http) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(http.getMcpEndpoint())
                .keepAliveInterval(http.getKeepAliveInterval())
                .disallowDelete(http.isDisallowDelete())
                .contextExtractor(request -> {
                    Object client = request.servletRequest().getAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE);
                    return McpTransportContext.create(Map.of(CLIENT_CONTEXT_KEY, client == null ? "unknown" : client));
                })
                .build();
    }

    @Bean
    RestClient mainApiRestClient(RestClient.Builder builder, McpProperties properties) {
        if (properties.mainApiKey() == null || properties.mainApiKey().isBlank()) {
            throw new IllegalStateException("jan.mcp.main-api-key(JAN_MCP_MAIN_API_KEY) 가 비었습니다");
        }
        return builder.baseUrl(properties.mainBaseUrl())
                .defaultHeader(MainApiClient.API_KEY_HEADER, properties.mainApiKey())
                .build();
    }
}
