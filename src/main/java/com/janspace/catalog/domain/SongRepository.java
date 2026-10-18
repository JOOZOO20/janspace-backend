package com.janspace.catalog.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SongRepository extends JpaRepository<Song, Long> {

    // 상세 조회는 앨범 번역까지 함께 가져온다. 곡 응답의 albumTitle 이 앨범마다 쿼리를 더 내지 않게 한다
    @Query("""
            select distinct s from Song s left join fetch s.album al left join fetch al.translations
            left join fetch s.translations left join fetch s.aliases where s.id = :id""")
    Optional<Song> findByIdWithDetails(@Param("id") Long id);

    @Query("""
            select distinct s from Song s left join fetch s.album al left join fetch al.translations
            left join fetch s.translations left join fetch s.aliases
            where s.id in :ids order by s.id""")
    List<Song> findAllByIdWithDetails(@Param("ids") Collection<Long> ids);

    @Query("""
            select distinct s from Song s left join fetch s.album al left join fetch al.translations
            left join fetch s.translations left join fetch s.aliases order by s.id""")
    List<Song> findAllWithDetails();

    // 한국어 제목으로 찾는다: 원어가 한국어면 본 컬럼, 아니면 ko 번역. 두 조건에 서로 다른 곡이 걸릴 수 있어 목록으로 받는다
    @Query("""
            select distinct s from Song s left join s.translations t
            where (s.originalLocale = 'ko' and s.title = :title)
               or (t.locale = 'ko' and t.text = :title)""")
    List<Song> findByKoreanTitle(@Param("title") String title);

    // 원어 제목·번역·별칭 어디든 대소문자 무시 부분 일치. 호출자가 fragment 를 소문자로 넘긴다. selectable 이 null 이면 선택 가능 여부를 가리지 않는다
    @Query("""
            select distinct s from Song s left join s.translations t left join s.aliases a
            where (:selectable is null or s.selectable = :selectable)
              and (lower(s.title) like concat('%', :fragment, '%')
                   or lower(t.text) like concat('%', :fragment, '%')
                   or a.alias like concat('%', :fragment, '%'))
            order by s.id""")
    List<Song> search(@Param("fragment") String fragment, @Param("selectable") Boolean selectable, Pageable pageable);

    Optional<Song> findByAliases_Alias(String alias);
}
