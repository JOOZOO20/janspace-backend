package com.stagelog.ops.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lease 저장소. 획득·Heartbeat·해제는 영향 행 수로 성공을 판정하는 조건부 UPDATE 이고, 만료 비교는 DB 시계 NOW(6) 로 한다.
 * 트랜잭션은 호출하는 JobLeases 가 연다.
 */
public interface JobLeaseRepository extends JpaRepository<JobLease, String> {

    // 영향 행 1 = 획득. 비어 있거나 만료된 행만 가져가고 token 을 1 올린다
    @Modifying
    @Query(value = """
            UPDATE job_leases
               SET owner_id = :owner, fencing_token = fencing_token + 1,
                   acquired_at = NOW(6), heartbeat_at = NOW(6), expires_at = NOW(6) + INTERVAL :ttl SECOND
             WHERE lease_key = :key AND (owner_id IS NULL OR expires_at < NOW(6))""", nativeQuery = true)
    int acquire(@Param("key") String key, @Param("owner") String owner, @Param("ttl") long ttlSeconds);

    // 영향 행 0 = Lease 상실 (다른 소유자가 만료 후 가져갔다)
    @Modifying
    @Query(value = """
            UPDATE job_leases
               SET heartbeat_at = NOW(6), expires_at = NOW(6) + INTERVAL :ttl SECOND
             WHERE lease_key = :key AND owner_id = :owner AND fencing_token = :token""", nativeQuery = true)
    int heartbeat(@Param("key") String key, @Param("owner") String owner, @Param("token") long token, @Param("ttl") long ttlSeconds);

    @Modifying
    @Query(value = """
            UPDATE job_leases
               SET owner_id = NULL, expires_at = NULL
             WHERE lease_key = :key AND owner_id = :owner AND fencing_token = :token""", nativeQuery = true)
    int release(@Param("key") String key, @Param("owner") String owner, @Param("token") long token);

    @Query(value = "SELECT fencing_token FROM job_leases WHERE lease_key = :key", nativeQuery = true)
    Optional<Long> findToken(@Param("key") String key);

    // SELECT ... FOR UPDATE. 종료 기록을 token 비교와 한 트랜잭션에 묶어, 비교 직후 다른 소유자가 획득하지 못하게 한다
    @Query(value = "SELECT fencing_token FROM job_leases WHERE lease_key = :key FOR UPDATE", nativeQuery = true)
    Optional<Long> findTokenForUpdate(@Param("key") String key);

    // 현재 소유자가 있고 만료 전인 Lease (409 응답용)
    @Query(value = "SELECT * FROM job_leases WHERE lease_key = :key AND owner_id IS NOT NULL AND expires_at >= NOW(6)",
            nativeQuery = true)
    Optional<JobLease> findHeld(@Param("key") String key);
}
