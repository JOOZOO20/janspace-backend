package com.janspace.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 애플리케이션 진입점. 루트 패키지가 com.janspace 이므로 스캔 범위를 명시한다.
 */
@SpringBootApplication(scanBasePackages = "com.janspace")
@EntityScan("com.janspace")
@ConfigurationPropertiesScan("com.janspace")
@EnableJpaRepositories("com.janspace")
public class JanspaceApplication {

    public static void main(String[] args) {
        SpringApplication.run(JanspaceApplication.class, args);
    }
}
