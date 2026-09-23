package com.janspace.bootstrap;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled 활성화. 스케줄 트리거 자체는 각 기능의 infrastructure 에 둔다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
