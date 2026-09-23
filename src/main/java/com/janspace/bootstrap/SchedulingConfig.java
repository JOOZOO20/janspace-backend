package com.janspace.bootstrap;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Scheduled·@Async 활성화. 스케줄 트리거와 비동기 리스너(예: setlist 재생목록 동기화) 자체는 각 기능의 infrastructure 에 둔다.
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {
}
