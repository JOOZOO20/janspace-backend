package com.stagelog.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MCP 진단 서버. MCP 클라이언트(Claude Code 등)에게 읽기 중심 진단 Tool 을 Streamable HTTP(/mcp)로 제공한다.
 * DB 에 직접 접근하지 않고 메인 앱 관리자 조회 API 를 MCP 전용 서비스 계정으로 부른다. 권한·마스킹은 메인 앱 한 곳에서 유지된다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JanMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JanMcpServerApplication.class, args);
    }
}
