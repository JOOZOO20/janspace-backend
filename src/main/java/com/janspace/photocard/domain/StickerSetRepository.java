package com.janspace.photocard.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StickerSetRepository extends JpaRepository<StickerSet, Long> {

    // stickers(List) 까지 함께 fetch join 하면 번역 수만큼 스티커가 중복되므로(bag) 번역만 조인하고 스티커는 트랜잭션 안에서 지연 로딩한다.
    // 스티커는 StickerSet.stickers 의 @BatchSize 로 세트 여러 개를 한 쿼리로 읽는다
    @Query("select distinct s from StickerSet s left join fetch s.translations where s.id = :id")
    Optional<StickerSet> findByIdWithDetails(@Param("id") Long id);

    @Query("select distinct s from StickerSet s left join fetch s.translations order by s.id")
    List<StickerSet> findAllWithDetails();
}
