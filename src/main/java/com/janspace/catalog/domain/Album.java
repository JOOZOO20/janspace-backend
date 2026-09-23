package com.janspace.catalog.domain;

import com.janspace.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 앨범. 제목 원어는 본 컬럼, 번역은 album_translations 에 필드·로케일당 한 행.
 * 마스터 데이터라 물리 삭제 경로가 없어 BaseEntity 만 상속한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "albums")
public class Album extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "original_locale", nullable = false, length = 10)
    private String originalLocale;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "cover_key", length = 255)
    private String coverKey;

    @Column(name = "itunes_collection_id")
    private Long itunesCollectionId;

    // List 두 개를 fetch join 하면 MultipleBagFetchException 이 나므로 Set 으로 둔다
    @OneToMany(mappedBy = "album", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<AlbumTranslation> translations = new HashSet<>();

    public Album(String title, String originalLocale) {
        this.title = title;
        this.originalLocale = originalLocale;
    }

    public void update(String title, String originalLocale, LocalDate releaseDate, Long itunesCollectionId) {
        this.title = title;
        this.originalLocale = originalLocale;
        this.releaseDate = releaseDate;
        this.itunesCollectionId = itunesCollectionId;
    }

    // 로케일 -> 텍스트 전체를 교체한다. 원어 로케일과 같은 키는 본 컬럼이 담당하므로 저장하지 않는다.
    // 같은 로케일 행은 제자리에서 갱신한다: 지우고 다시 넣으면 Hibernate 가 INSERT 를 DELETE 보다 먼저 흘려 유일 제약에 걸린다
    public void replaceTranslations(Map<String, String> byLocale, TranslationSource source) {
        Map<String, String> wanted = new HashMap<>();
        byLocale.forEach((locale, text) -> {
            if (!locale.equals(originalLocale) && text != null && !text.isBlank()) {
                wanted.put(locale, text.trim());
            }
        });
        translations.removeIf(existing -> !wanted.containsKey(existing.getLocale()));
        translations.forEach(existing -> existing.changeText(wanted.remove(existing.getLocale()), source));
        wanted.forEach((locale, text) -> translations.add(new AlbumTranslation(this, TextField.TITLE, locale, text, source)));
    }

    public void changeCoverKey(String coverKey) {
        this.coverKey = coverKey;
    }
}
