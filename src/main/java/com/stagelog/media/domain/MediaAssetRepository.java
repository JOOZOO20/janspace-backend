package com.stagelog.media.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 원본 asset 저장소. 트랜잭션은 호출하는 application 이 연다.
 */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    Optional<MediaAsset> findByOwnerTypeAndOwnerIdAndOriginalSha256(String ownerType, Long ownerId, String originalSha256);

    boolean existsByOwnerTypeAndOwnerIdAndOriginalKey(String ownerType, Long ownerId, String originalKey);

    // 생성 시각을 DB 시계로 다시 적는다. 처리 지연(created_at -> rendition READY 의 updated_at)을 같은 시계로 재기 위해서다
    @Modifying
    @Query(value = "UPDATE media_assets SET created_at = NOW(6), updated_at = NOW(6) WHERE id = :id", nativeQuery = true)
    int stampCreatedNow(@Param("id") Long id);

    // asset 행 잠금. rendition 완료·실패와 집계를 같은 asset 단위로 줄 세운다. 먼저 잡지 않으면 두 Worker 가 서로의 rendition 행을
    // 집계 쿼리로 읽으며(공유 잠금) 기다려 deadlock 이 난다
    @Query(value = "SELECT id FROM media_assets WHERE id = :id FOR UPDATE", nativeQuery = true)
    Long lockById(@Param("id") Long id);

    // rendition 상태 집계. 같은 트랜잭션에서 lockById 를 먼저 부른다. 끝나지 않은 rendition 이 있으면 PENDING, 모두 READY 면 READY, 하나도 READY 가 아니면 FAILED, 그 밖은 PARTIAL
    @Modifying
    @Query(value = """
            UPDATE media_assets a
              JOIN (SELECT asset_id, COUNT(*) AS total, SUM(status = 'READY') AS ready, SUM(status = 'FAILED') AS failed
                      FROM media_renditions WHERE asset_id = :id GROUP BY asset_id) r ON r.asset_id = a.id
               SET a.status = CASE WHEN r.ready = r.total THEN 'READY'
                                   WHEN r.ready + r.failed < r.total THEN 'PENDING'
                                   WHEN r.ready = 0 THEN 'FAILED'
                                   ELSE 'PARTIAL' END,
                   a.updated_at = NOW(6)
             WHERE a.id = :id""", nativeQuery = true)
    int aggregate(@Param("id") Long id);
}
