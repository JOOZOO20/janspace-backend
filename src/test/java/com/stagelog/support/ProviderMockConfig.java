package com.stagelog.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 통합 테스트에서 외부 HTTP(네이버·구글·카카오·LocationIQ)를 MockRestServiceServer 로 대체한다.
 * 싱글턴 RestClient.Builder 를 먼저 mock 서버에 묶어 두므로 모든 integration 클라이언트가 같은 mock 을 쓴다.
 * test 프로파일에서만 등록되며 Boot 의 prototype RestClient.Builder 를 대신한다.
 */
@Configuration
@Profile("test")
public class ProviderMockConfig {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();

    @Bean
    @Primary
    public RestClient.Builder mockedRestClientBuilder() {
        return builder;
    }

    @Bean
    public MockRestServiceServer providerMockServer() {
        return server;
    }
}
