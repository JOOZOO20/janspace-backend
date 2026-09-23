package com.janspace.catalog.application;

import com.janspace.catalog.application.port.CoverStoragePort;
import com.janspace.catalog.domain.Album;
import com.janspace.catalog.domain.AlbumRepository;
import com.janspace.catalog.domain.AliasNormalizer;
import com.janspace.catalog.domain.Song;
import com.janspace.catalog.domain.SongRepository;
import com.janspace.common.locale.LocalizedText;
import com.janspace.common.locale.SupportedLocale;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * catalog 의 공개 조회 계약. setlist 는 matchSetlistFmName 으로 setlist.fm 표기를 곡에 연결하고,
 * attendance 는 findSong/findSongs 로 최고의 곡을 보여준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogQuery {

    private final SongRepository songs;
    private final AlbumRepository albums;
    private final CoverStoragePort covers;

    public Optional<SongSnapshot> findSong(Long id) {
        return songs.findByIdWithDetails(id).map(this::toSnapshot);
    }

    public List<SongSnapshot> findSongs(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return songs.findAllByIdWithDetails(ids).stream().map(this::toSnapshot).toList();
    }

    /**
     * setlist.fm 표기를 곡 ID 로 연결한다. 1) 정규화한 별칭 정확 일치 2) 원어 제목·번역 제목을 같은 규칙으로 정규화해 일치.
     * 둘 다 없으면 empty (호출자가 미매칭 큐에 넣는다).
     */
    public Optional<Long> matchSetlistFmName(String raw) {
        String normalized = AliasNormalizer.normalize(raw);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        Optional<Long> byAlias = songs.findByAliases_Alias(normalized).map(Song::getId);
        if (byAlias.isPresent()) {
            return byAlias;
        }
        // 한계: 전곡(100곡 안팎) 을 훑는다. 곡 수가 수천이 되면 정규화 제목 컬럼을 두고 조회로 바꾼다
        return songs.findAllWithDetails().stream()
                .filter(song -> AliasNormalizer.normalize(song.getTitle()).equals(normalized)
                        || song.getTranslations().stream()
                                .anyMatch(t -> AliasNormalizer.normalize(t.getText()).equals(normalized)))
                .map(Song::getId)
                .findFirst();
    }

    // 원어 제목·번역·별칭 어디든 대소문자 무시 부분 일치. q 가 비면 전곡
    public List<SongSnapshot> search(String q, int limit) {
        List<Song> found = q == null || q.isBlank()
                ? songs.findAllWithDetails()
                : songs.search(q.trim().toLowerCase(Locale.ROOT), PageRequest.of(0, limit));
        List<Song> detailed = q == null || q.isBlank() ? found
                : songs.findAllByIdWithDetails(found.stream().map(Song::getId).toList());
        return detailed.stream().limit(limit).map(this::toSnapshot).toList();
    }

    public List<AlbumSnapshot> findAlbums() {
        return albums.findAllWithTranslations().stream().map(this::toSnapshot).toList();
    }

    SongSnapshot toSnapshot(Song song) {
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
                album == null ? null : coverUrl(album));
    }

    AlbumSnapshot toSnapshot(Album album) {
        return new AlbumSnapshot(
                album.getId(),
                localized(album.getTitle(), album.getOriginalLocale(),
                        album.getTranslations().stream().collect(Collectors.toMap(
                                t -> t.getLocale(), t -> t.getText(), (a, b) -> a))),
                album.getReleaseDate(),
                coverUrl(album),
                album.getItunesCollectionId());
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
