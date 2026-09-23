package com.janspace.catalog.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlbumRepository extends JpaRepository<Album, Long> {

    // 한국어 제목으로 찾는다: 원어가 한국어면 본 컬럼, 아니면 ko 번역
    @Query("""
            select distinct a from Album a left join a.translations t
            where (a.originalLocale = 'ko' and a.title = :title)
               or (t.locale = 'ko' and t.text = :title)""")
    Optional<Album> findByKoreanTitle(@Param("title") String title);

    @Query("select distinct a from Album a left join fetch a.translations order by a.releaseDate, a.id")
    List<Album> findAllWithTranslations();
}
