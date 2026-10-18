package com.janspace.catalog.domain;

import com.janspace.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 곡 마스터. 제목 원어는 본 컬럼, 번역은 song_translations, setlist.fm 표기는 song_aliases.
 * album 은 대표 앨범이며 없을 수 있다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "songs")
public class Song extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "album_id")
    private Album album;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "original_locale", nullable = false, length = 10)
    private String originalLocale;

    @Column(name = "itunes_track_id")
    private Long itunesTrackId;

    @Column(name = "youtube_video_id", length = 20)
    private String youtubeVideoId;

    // List 두 개를 fetch join 하면 MultipleBagFetchException 이 나므로 Set 으로 둔다
    @OneToMany(mappedBy = "song", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<SongTranslation> translations = new HashSet<>();

    @OneToMany(mappedBy = "song", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<SongAlias> aliases = new HashSet<>();

    public Song(String title, String originalLocale) {
        this.title = title;
        this.originalLocale = originalLocale;
    }

    public void update(String title, String originalLocale, Long itunesTrackId, String youtubeVideoId, Album album) {
        this.title = title;
        this.originalLocale = originalLocale;
        this.itunesTrackId = itunesTrackId;
        this.youtubeVideoId = youtubeVideoId;
        this.album = album;
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
        wanted.forEach((locale, text) -> translations.add(new SongTranslation(this, TextField.TITLE, locale, text, source)));
    }

    // 정규화한 별칭 집합으로 교체한다. 이미 같은 값이 있으면 행을 유지해 불필요한 delete/insert 를 피한다
    public void replaceAliases(Collection<String> rawAliases) {
        Set<String> wanted = new LinkedHashSet<>();
        for (String raw : rawAliases) {
            String normalized = AliasNormalizer.normalize(raw);
            if (!normalized.isBlank()) {
                wanted.add(normalized);
            }
        }
        aliases.removeIf(existing -> !wanted.remove(existing.getAlias()));
        wanted.forEach(alias -> aliases.add(new SongAlias(this, alias)));
    }

    public List<String> aliasValues() {
        return aliases.stream().map(SongAlias::getAlias).toList();
    }
}
