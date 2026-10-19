package com.stagelog.catalog.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlbumRepository extends JpaRepository<Album, Long> {

    // 한국어 제목으로 찾는다: 원어가 한국어면 본 컬럼, 아니면 ko 번역. 두 조건에 서로 다른 앨범이 걸릴 수 있어 목록으로 받는다
    @Query("""
            select distinct a from Album a left join a.translations t
            where (a.originalLocale = 'ko' and a.title = :title)
               or (t.locale = 'ko' and t.text = :title)""")
    List<Album> findByKoreanTitle(@Param("title") String title);

    @Query("select distinct a from Album a left join fetch a.translations order by a.releaseDate, a.id")
    List<Album> findAllWithTranslations();
}
