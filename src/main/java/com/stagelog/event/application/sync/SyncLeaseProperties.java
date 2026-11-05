package com.stagelog.event.application.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 동기화 실행 Lease 설정 (jan.sync.lease). enabled=false 면 Lease 대신 JVM 안 CAS 하나로 막던 이전 동작으로 돌아간다(롤백·전후 비교용).
 */
@ConfigurationProperties(prefix = "jan.sync.lease")
public record SyncLeaseProperties(boolean enabled, long ttlSeconds, long heartbeatSeconds) {
}
