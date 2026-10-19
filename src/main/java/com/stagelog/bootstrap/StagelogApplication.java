package com.stagelog.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 애플리케이션 진입점. 루트 패키지가 com.stagelog 이므로 스캔 범위를 명시한다.
 */
@SpringBootApplication(scanBasePackages = "com.stagelog")
@EntityScan("com.stagelog")
@ConfigurationPropertiesScan("com.stagelog")
@EnableJpaRepositories("com.stagelog")
public class StagelogApplication {

    public static void main(String[] args) {
        SpringApplication.run(StagelogApplication.class, args);
    }
}
