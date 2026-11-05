package com.stagelog.media.domain;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * rendition 저장소. claim·연장·완료·실패는 영향 행 수로 성공을 판정하는 조건부 UPDATE 이고 만료 비교는 DB 시계 NOW(6) 다.
 * 완료·실패·반납은 자기 Lease(lease_owner) 일 때만 반영된다. 트랜잭션은 호출하는 application 이 연다.
 */
public interface MediaRenditionRepository extends JpaRepository<MediaRendition, Long> {

    List<MediaRendition> findAllByAssetIdOrderById(Long assetId);

    // 영향 행 1 = 이 Worker 가 처리. 0 = 다른 Worker 가 처리 중이거나 이미 끝났다
    @Modifying
    @Query(value = """
            UPDATE media_renditions
               SET status = 'PROCESSING', lease_owner = :owner, attempt = attempt + 1,
                   lease_expires_at = NOW(6) + INTERVAL :lease SECOND, updated_at = NOW(6)
             WHERE id = :id
               AND (status = 'PENDING' OR (status = 'PROCESSING' AND lease_expires_at < NOW(6)))""", nativeQuery = true)
    int claim(@Param("id") Long id, @Param("owner") String owner, @Param("lease") long leaseSeconds);

    // 처리 중 30초마다. 영향 행 0 = Lease 를 잃었다
    @Modifying
    @Query(value = """
            UPDATE media_renditions
               SET lease_expires_at = NOW(6) + INTERVAL :lease SECOND
             WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner""", nativeQuery = true)
    int extendLease(@Param("id") Long id, @Param("owner") String owner, @Param("lease") long leaseSeconds);

    @Modifying
    @Query(value = """
            UPDATE media_renditions
               SET status = 'READY', output_key = :key, width = :w, height = :h, bytes = :bytes, last_error = NULL,
                   lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW(6)
             WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner""", nativeQuery = true)
    int complete(@Param("id") Long id, @Param("owner") String owner, @Param("key") String key,
                 @Param("w") int width, @Param("h") int height, @Param("bytes") long bytes);

    // 재시도하지 않는 실패
    @Modifying
    @Query(value = """
            UPDATE media_renditions
               SET status = 'FAILED', last_error = :error, lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW(6)
             WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner""", nativeQuery = true)
    int fail(@Param("id") Long id, @Param("owner") String owner, @Param("error") String error);

    // 일시 실패: PENDING 으로 돌려 SQS 재전달이 바로 claim 할 수 있게 한다
    @Modifying
    @Query(value = """
            UPDATE media_renditions
               SET status = 'PENDING', last_error = :error, lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW(6)
             WHERE id = :id AND status = 'PROCESSING' AND lease_owner = :owner""", nativeQuery = true)
    int release(@Param("id") Long id, @Param("owner") String owner, @Param("error") String error);

    // 관리자 승인 재시도: FAILED 만 PENDING 으로 돌리고 시도 횟수를 새로 센다 (Worker 가 MAX_ATTEMPTS 를 처음부터 쓴다). last_error 는 다음 결과가 덮어쓸 때까지 남긴다
    @Modifying
    @Query(value = """
            UPDATE media_renditions
               SET status = 'PENDING', attempt = 0, lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW(6)
             WHERE id = :id AND status = 'FAILED'""", nativeQuery = true)
    int resetFailed(@Param("id") Long id);

    /** Lease 가 만료된 PROCESSING (Worker 가 죽었다). idx_status_lease 를 탄다. */
    interface Expired {
        Long getId();

        Long getAssetId();
    }

    @Query(value = """
            SELECT id, asset_id AS assetId FROM media_renditions
             WHERE status = 'PROCESSING' AND lease_expires_at < NOW(6)
             ORDER BY lease_expires_at LIMIT :limit""", nativeQuery = true)
    List<Expired> findExpired(@Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE media_renditions
               SET status = 'PENDING', lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW(6)
             WHERE id = :id AND status = 'PROCESSING' AND lease_expires_at < NOW(6) AND attempt < :max""", nativeQuery = true)
    int resetExpired(@Param("id") Long id, @Param("max") int maxAttempts);

    @Modifying
    @Query(value = """
            UPDATE media_renditions
               SET status = 'FAILED', last_error = :error, lease_owner = NULL, lease_expires_at = NULL, updated_at = NOW(6)
             WHERE id = :id AND status = 'PROCESSING' AND lease_expires_at < NOW(6) AND attempt >= :max""", nativeQuery = true)
    int failExpired(@Param("id") Long id, @Param("max") int maxAttempts, @Param("error") String error);

    /** 서빙용 rendition. original_key 가 소유자의 현재 key 인 asset 의 rendition 전부(상태 포함). */
    interface Ready {
        Long getAssetId();

        Long getOwnerId();

        String getOriginalKey();

        String getProfile();

        String getFormat();

        String getOutputKey();

        String getStatus();
    }

    @Query(value = """
            SELECT a.id AS assetId, a.owner_id AS ownerId, a.original_key AS originalKey, r.profile AS profile, r.format AS format,
                   r.output_key AS outputKey, r.status AS status
              FROM media_assets a JOIN media_renditions r ON r.asset_id = a.id
             WHERE a.owner_type = :type AND a.owner_id IN (:ownerIds) AND a.original_key IN (:keys)""",
            nativeQuery = true)
    List<Ready> findReady(@Param("type") String ownerType, @Param("ownerIds") Collection<Long> ownerIds,
                          @Param("keys") Collection<String> originalKeys);
}
