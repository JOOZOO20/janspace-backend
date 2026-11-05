package com.stagelog.catalog.application;

import com.stagelog.catalog.application.port.CoverStoragePort;
import com.stagelog.catalog.domain.Album;
import com.stagelog.catalog.domain.AlbumRepository;
import com.stagelog.catalog.domain.AliasNormalizer;
import com.stagelog.catalog.domain.Song;
import com.stagelog.catalog.domain.SongRepository;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.media.application.ImageSet;
import com.stagelog.media.application.MediaImages;
import com.stagelog.media.application.MediaOwnerType;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog 의 공개 조회 계약. setlist 는 matchSetlistFmNames 로 setlist.fm 표기를 곡에 연결하고,
 * attendance 는 findSong/findSongs 로 최고의 곡을 보여준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogQuery {

    private final SongRepository songs;
    private final AlbumRepository albums;
    private final CoverStoragePort covers;
    private final MediaImages mediaImages;

    public Optional<SongSnapshot> findSong(Long id) {
        return songs.findByIdWithDetails(id).map(this::toSnapshot);
    }

    public List<SongSnapshot> findSongs(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return snapshots(songs.findAllByIdWithDetails(ids));
    }

    /**
     * setlist.fm 표기들을 곡 ID 로 연결한다. 1) 정규화한 별칭 정확 일치 2) 원어 제목·번역 제목을 같은 규칙으로 정규화해 일치.
     * 맞은 표기만 결과에 들어간다 (나머지는 호출자가 미매칭 큐에 넣는다). 2) 가 필요할 때만 전곡을 한 번 읽는다.
     */
    public Map<String, Long> matchSetlistFmNames(Collection<String> raws) {
        Map<String, Long> matched = new HashMap<>();
        List<Song> all = null;
        for (String raw : raws) {
            String normalized = AliasNormalizer.normalize(raw);
            if (normalized.isBlank() || matched.containsKey(raw)) {
                continue;
            }
            Optional<Long> byAlias = songs.findByAliases_Alias(normalized).map(Song::getId);
            if (byAlias.isPresent()) {
                matched.put(raw, byAlias.get());
                continue;
            }
            // 한계: 전곡(100곡 안팎)을 메모리에서 훑는다. 곡 수가 수천이 되면 정규화 제목 컬럼을 두고 조회로 바꾼다
            if (all == null) {
                all = songs.findAllWithDetails();
            }
            all.stream()
                    .filter(song -> AliasNormalizer.normalize(song.getTitle()).equals(normalized)
                            || song.getTranslations().stream()
                                    .anyMatch(t -> AliasNormalizer.normalize(t.getText()).equals(normalized)))
                    .map(Song::getId)
                    .findFirst()
                    .ifPresent(id -> matched.put(raw, id));
        }
        return matched;
    }

    /**
     * 원어 제목·번역·별칭 어디든 대소문자 무시 부분 일치. q 가 비면 텍스트 조건 없이 전곡.
     * selectable 이 null 이면 선택 가능 여부를 가리지 않고, true/false 면 그 값인 곡만. limit 이 null 이면 개수 제한 없음.
     */
    public List<SongSnapshot> search(String q, Boolean selectable, Integer limit) {
        if (q == null || q.isBlank()) {
            return snapshots(songs.findAllWithDetails().stream()
                    .filter(song -> selectable == null || song.isSelectable() == selectable)
                    .limit(limit == null ? Long.MAX_VALUE : limit)
                    .toList());
        }
        Pageable page = limit == null ? Pageable.unpaged() : PageRequest.of(0, limit);
        // 검색어의 % _ 는 글자로 찾는다 (SongRepository.search 의 escape '!')
        String fragment = q.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_");
        List<Long> ids = songs.search(fragment, selectable, page).stream().map(Song::getId).toList();
        return findSongs(ids);
    }

    public List<AlbumSnapshot> findAlbums() {
        List<Album> all = albums.findAllWithTranslations();
        Map<Long, ImageSet> images = coverImages(all);
        return all.stream().map(album -> toSnapshot(album, images)).toList();
    }

    SongSnapshot toSnapshot(Song song) {
        return snapshots(List.of(song)).get(0);
    }

    // 커버 images 를 쿼리 한 번으로 묶는다
    private List<SongSnapshot> snapshots(List<Song> list) {
        Map<Long, ImageSet> images = coverImages(list.stream().map(Song::getAlbum).filter(Objects::nonNull).distinct().toList());
        return list.stream().map(song -> toSnapshot(song, images)).toList();
    }

    private SongSnapshot toSnapshot(Song song, Map<Long, ImageSet> images) {
        Album album = song.getAlbum();
        return new SongSnapshot(
                song.getId(),
                localized(song.getTitle(), song.getOriginalLocale(),
                        song.getTranslations().stream().collect(Collectors.toMap(
                                t -> t.getLocale(), t -> t.getText(), (a, b) -> a))),
                song.aliasValues(),
                song.getItunesTrackId(),
                song.getYoutubeVideoId(),
                album == null ? null : album.getId(),
                album == null ? null : albumTitle(album),
                album == null ? null : coverUrl(album),
                album == null ? null : images.get(album.getId()),
                song.isSelectable());
    }

    AlbumSnapshot toSnapshot(Album album) {
        return toSnapshot(album, coverImages(List.of(album)));
    }

    private AlbumSnapshot toSnapshot(Album album, Map<Long, ImageSet> images) {
        return new AlbumSnapshot(
                album.getId(),
                albumTitle(album),
                album.getReleaseDate(),
                coverUrl(album),
                images.get(album.getId()),
                album.getItunesCollectionId());
    }

    private Map<Long, ImageSet> coverImages(List<Album> list) {
        return mediaImages.of(MediaOwnerType.ALBUM,
                list.stream().map(a -> new MediaImages.Original(a.getId(), a.getCoverKey(), coverUrl(a))).toList());
    }

    private static LocalizedText albumTitle(Album album) {
        return localized(album.getTitle(), album.getOriginalLocale(),
                album.getTranslations().stream().collect(Collectors.toMap(t -> t.getLocale(), t -> t.getText(), (a, b) -> a)));
    }

    private String coverUrl(Album album) {
        return album.getCoverKey() == null ? null : covers.publicUrl(album.getCoverKey());
    }

    // 원어 로케일 키도 넣는다. 프론트 Song.title 은 ko 키를 필수로 기대한다
    static LocalizedText localized(String original, String originalLocale, Map<String, String> translations) {
        EnumMap<SupportedLocale, String> map = new EnumMap<>(SupportedLocale.class);
        SupportedLocale.fromTag(originalLocale).ifPresent(locale -> map.put(locale, original));
        translations.forEach((tag, text) -> SupportedLocale.fromTag(tag).ifPresent(locale -> map.put(locale, text)));
        return new LocalizedText(original, map);
    }
}
