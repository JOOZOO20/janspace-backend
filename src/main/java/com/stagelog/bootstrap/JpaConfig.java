package com.stagelog.bootstrap;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화. BaseEntity 의 createdAt/updatedAt 을 채운다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
