package com.stagelog.ops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 잡 하나의 Lease 행. 행은 마이그레이션이 미리 넣고, 상태 전이는 JobLeaseRepository 의 조건부 UPDATE 로만 한다(엔티티로 쓰지 않는다).
 * 생성·수정 시각 대신 acquired_at·heartbeat_at 을 DB 시계로 남기므로 BaseEntity 를 쓰지 않는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "job_leases")
public class JobLease {

    @Id
    @Column(name = "lease_key", length = 100)
    private String leaseKey;

    @Column(name = "owner_id", length = 100)
    private String ownerId;

    @Column(name = "fencing_token", nullable = false)
    private Long fencingToken;

    @Column(name = "acquired_at", columnDefinition = "DATETIME(6)")
    private Instant acquiredAt;

    @Column(name = "heartbeat_at", columnDefinition = "DATETIME(6)")
    private Instant heartbeatAt;

    @Column(name = "expires_at", columnDefinition = "DATETIME(6)")
    private Instant expiresAt;
}
