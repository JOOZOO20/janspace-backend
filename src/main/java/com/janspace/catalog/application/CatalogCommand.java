package com.janspace.catalog.application;

import com.janspace.catalog.application.port.CoverStoragePort;
import com.janspace.catalog.application.port.SongSheetReader;
import com.janspace.catalog.application.port.SongSheetRow;
import com.janspace.catalog.application.port.TrackCandidate;
import com.janspace.catalog.application.port.TrackLookupPort;
import com.janspace.catalog.domain.Album;
import com.janspace.catalog.domain.AlbumRepository;
import com.janspace.catalog.domain.AliasNormalizer;
import com.janspace.catalog.domain.Song;
import com.janspace.catalog.domain.SongRepository;
import com.janspace.catalog.domain.TranslationSource;
import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.common.locale.LocalizedText;
import com.janspace.common.locale.SupportedLocale;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 카탈로그 유스케이스: 곡·앨범 upsert, 엑셀 import, 커버 업로드, iTunes 후보 조회.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogCommand {

    private static final String DEFAULT_LOCALE = SupportedLocale.KO.tag();
    private static final String ITUNES_COUNTRY = "kr";
    static final long MAX_COVER_BYTES = 5L * 1024 * 1024;

    private final SongRepository songs;
    private final AlbumRepository albums;
    private final CoverStoragePort covers;
    private final TrackLookupPort tracks;
    private final SongSheetReader sheetReader;
    private final CatalogQuery query;

    @Transactional
    public SongSnapshot upsertSong(UpsertSong cmd) {
        String originalLocale = localeOrDefault(cmd.originalLocale());
        Map<String, String> translations = tagMap(cmd.title());
        List<String> aliases = cmd.aliases() == null ? List.of() : cmd.aliases();

        // 1. 대표 앨범 확인 (없는 id 면 404)
        Album album = cmd.albumId() == null ? null : albums.findById(cmd.albumId())
                .orElseThrow(() -> new NotFoundException(ErrorCode.ALBUM_NOT_FOUND));

        // 2. 대상 곡: id 우선, 없으면 한국어 제목 일치, 그것도 없으면 신규
        Optional<Song> existing = cmd.id() != null
                ? Optional.of(songs.findById(cmd.id()).orElseThrow(() -> new NotFoundException(ErrorCode.SONG_NOT_FOUND)))
                : koreanTitle(cmd.title(), originalLocale).flatMap(songs::findByKoreanTitle);

        // 3. 별칭 충돌은 변경 전에 검사한다. 별칭은 전체 곡에서 유일하다
        rejectAliasesOwnedByOthers(aliases, existing.map(Song::getId).orElse(null));

        // 4. 저장
        Song song = existing.orElseGet(() -> new Song(cmd.title().original().trim(), originalLocale));
        song.update(cmd.title().original().trim(), originalLocale, cmd.itunesTrackId(), blankToNull(cmd.youtubeVideoId()), album);
        song.replaceTranslations(translations, TranslationSource.ADMIN);
        song.replaceAliases(aliases);
        Song saved = songs.save(song);
        log.info("[곡 upsert] songId={}, 신규={}, 별칭수={}", saved.getId(), existing.isEmpty(), saved.getAliases().size());
        return query.findSong(saved.getId()).orElseThrow();
    }

    @Transactional
    public AlbumSnapshot upsertAlbum(UpsertAlbum cmd) {
        String originalLocale = localeOrDefault(cmd.originalLocale());
        Optional<Album> existing = cmd.id() != null
                ? Optional.of(albums.findById(cmd.id()).orElseThrow(() -> new NotFoundException(ErrorCode.ALBUM_NOT_FOUND)))
                : koreanTitle(cmd.title(), originalLocale).flatMap(albums::findByKoreanTitle);
        Album album = existing.orElseGet(() -> new Album(cmd.title().original().trim(), originalLocale));
        album.update(cmd.title().original().trim(), originalLocale, cmd.releaseDate(), cmd.itunesCollectionId());
        album.replaceTranslations(tagMap(cmd.title()), TranslationSource.ADMIN);
        Album saved = albums.save(album);
        log.info("[앨범 upsert] albumId={}, 신규={}", saved.getId(), existing.isEmpty());
        return query.toSnapshot(saved);
    }

    /**
     * 엑셀 import. 한국어 제목이 빈 행은 skipped, 행 단위 오류(iTunes ID 숫자 아님, 별칭 충돌 등)는 errors 에 남기고 계속한다.
     * 오류 행 외에는 저장되므로 같은 파일을 다시 올리면 updated 로 집계된다.
     */
    @Transactional
    public ImportReport importSongs(InputStream xlsx) {
        List<SongSheetRow> rows = sheetReader.read(xlsx);
        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (SongSheetRow row : rows) {
            if (row.ko() == null || row.ko().isBlank()) {
                skipped++;
                continue;
            }
            try {
                // 1. 앨범: 한국어 제목으로 찾고 없으면 만든다
                Album album = row.albumTitle() == null || row.albumTitle().isBlank() ? null
                        : albums.findByKoreanTitle(row.albumTitle().trim())
                                .orElseGet(() -> albums.save(new Album(row.albumTitle().trim(), DEFAULT_LOCALE)));
                // 2. 곡: 한국어 제목 일치 여부로 created/updated 집계
                boolean exists = songs.findByKoreanTitle(row.ko().trim()).isPresent();
                Map<SupportedLocale, String> translations = new HashMap<>();
                translations.put(SupportedLocale.EN, blankToNull(row.en()));
                translations.put(SupportedLocale.ZH_TW, blankToNull(row.zhTw()));
                translations.put(SupportedLocale.JA, blankToNull(row.ja()));
                List<String> aliases = row.aliases() == null ? List.of()
                        : Arrays.stream(row.aliases().split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                upsertSong(new UpsertSong(null, new LocalizedText(row.ko().trim(), translations), DEFAULT_LOCALE,
                        aliases, parseLong(row.itunesTrackId(), row.rowNumber()), blankToNull(row.youtubeVideoId()),
                        album == null ? null : album.getId()));
                if (exists) {
                    updated++;
                } else {
                    created++;
                }
            } catch (ApplicationException e) {
                log.warn("[곡 import - 행 실패] row={}, reason={}", row.rowNumber(), e.getMessage());
                errors.add(row.rowNumber() + "행: " + e.getMessage());
            }
        }
        log.info("[곡 import 완료] created={}, updated={}, skipped={}, errors={}", created, updated, skipped, errors.size());
        return new ImportReport(created, updated, skipped, errors);
    }

    // 커버는 PNG/JPEG 5MB 이하만. 형식은 헤더가 아니라 파일 앞 바이트로 판정한다
    @Transactional
    public String uploadCover(Long albumId, byte[] bytes) {
        Album album = albums.findById(albumId).orElseThrow(() -> new NotFoundException(ErrorCode.ALBUM_NOT_FOUND));
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_COVER_BYTES) {
            throw new BadRequestException(ErrorCode.COVER_INVALID);
        }
        String ext = imageExtension(bytes);
        String contentType = "png".equals(ext) ? "image/png" : "image/jpeg";
        String key = "albums/" + albumId + "." + ext;
        // 외부 호출: S3 업로드
        covers.put(key, bytes, contentType);
        album.changeCoverKey(key);
        log.info("[앨범커버 업로드] albumId={}, key={}, bytes={}", albumId, key, bytes.length);
        return covers.publicUrl(key);
    }

    // setlist 미매칭 큐에서 곡명을 곡에 연결한다. 이미 그 곡의 별칭이면 그대로, 다른 곡의 별칭이면 409
    @Transactional
    public void addAlias(Long songId, String alias) {
        Song song = songs.findByIdWithDetails(songId).orElseThrow(() -> new NotFoundException(ErrorCode.SONG_NOT_FOUND));
        rejectAliasesOwnedByOthers(List.of(alias), songId);
        List<String> merged = new ArrayList<>(song.aliasValues());
        merged.add(alias);
        song.replaceAliases(merged);
        log.info("[별칭 추가] songId={}, alias={}", songId, AliasNormalizer.normalize(alias));
    }

    public List<TrackCandidate> lookupTracks(String term) {
        // 외부 호출: iTunes Search (키 없음)
        return tracks.search(term, ITUNES_COUNTRY);
    }

    private void rejectAliasesOwnedByOthers(List<String> aliases, Long selfId) {
        for (String raw : aliases) {
            String normalized = AliasNormalizer.normalize(raw);
            if (normalized.isBlank()) {
                continue;
            }
            songs.findByAliases_Alias(normalized)
                    .filter(owner -> !owner.getId().equals(selfId))
                    .ifPresent(owner -> {
                        throw new ConflictException(ErrorCode.CONFLICT,
                                "별칭 '" + normalized + "' 은 이미 다른 곡(id=" + owner.getId() + ") 에 연결되어 있습니다.");
                    });
        }
    }

    private static String localeOrDefault(String tag) {
        if (tag == null || tag.isBlank()) {
            return DEFAULT_LOCALE;
        }
        return SupportedLocale.fromTag(tag)
                .map(SupportedLocale::tag)
                .orElseThrow(() -> new BadRequestException(ErrorCode.INVALID_INPUT, "지원하지 않는 로케일입니다: " + tag));
    }

    // 한국어 제목 = 원어가 ko 면 원어, 아니면 ko 번역
    private static Optional<String> koreanTitle(LocalizedText title, String originalLocale) {
        if (DEFAULT_LOCALE.equals(originalLocale)) {
            return Optional.of(title.original().trim());
        }
        return title.translation(SupportedLocale.KO).map(String::trim);
    }

    private static Map<String, String> tagMap(LocalizedText title) {
        Map<String, String> out = new HashMap<>();
        title.translations().forEach((locale, text) -> out.put(locale.tag(), text));
        return out;
    }

    private static Long parseLong(String raw, int rowNumber) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException(ErrorCode.CATALOG_IMPORT_INVALID, "iTunes 곡 ID 가 숫자가 아닙니다: " + raw);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // PNG 시그니처 89 50 4E 47, JPEG 시그니처 FF D8 FF
    private static String imageExtension(byte[] bytes) {
        if (bytes.length >= 4 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        throw new BadRequestException(ErrorCode.COVER_INVALID);
    }
}
