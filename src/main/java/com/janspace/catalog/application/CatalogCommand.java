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
import java.util.EnumMap;
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
    // 컬럼 길이: 제목·번역·별칭 VARCHAR(255), 유튜브 영상 ID VARCHAR(20). 별칭 개수는 관리자 입력 상한
    private static final int TEXT_MAX = 255;
    private static final int YOUTUBE_ID_MAX = 20;
    private static final int ALIASES_MAX = 50;

    private final SongRepository songs;
    private final AlbumRepository albums;
    private final CoverStoragePort covers;
    private final TrackLookupPort tracks;
    private final SongSheetReader sheetReader;
    private final CatalogQuery query;

    /**
     * 곡 저장 (병합). 보낸 값만 반영하고 null 인 필드는 기존 값을 둔다 (UpsertSong 참고).
     * 관리자 폼이 보내지 않는 필드(앨범 연결, 선택 가능 여부)를 저장할 때마다 지우지 않기 위해서다.
     */
    @Transactional
    public SongSnapshot upsertSong(UpsertSong cmd) {
        // 1. 대상 곡: id 우선, 없으면 한국어 제목 일치, 그것도 없으면 신규 (신규는 제목 필수)
        String requestedLocale = cmd.originalLocale() == null ? null : locale(cmd.originalLocale());
        Optional<Song> existing = cmd.id() != null
                ? Optional.of(songs.findById(cmd.id()).orElseThrow(() -> new NotFoundException(ErrorCode.SONG_NOT_FOUND)))
                : Optional.ofNullable(cmd.title())
                        .flatMap(title -> koreanTitle(title, requestedLocale == null ? DEFAULT_LOCALE : requestedLocale))
                        .flatMap(title -> single(songs.findByKoreanTitle(title), title));
        if (existing.isEmpty() && cmd.title() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "새 곡에는 title 이 필요합니다.");
        }
        if (cmd.title() != null) {
            requireValidTitle(cmd.title());
        }
        List<String> aliases = cmd.aliases() == null ? null : cmd.aliases().orElse(List.of());
        if (aliases != null) {
            requireValidAliases(aliases);
        }
        if (cmd.youtubeVideoId() != null) {
            cmd.youtubeVideoId().ifPresent(id -> requireMaxLength("youtubeId", id.trim(), YOUTUBE_ID_MAX));
        }

        // 2. 대표 앨범 확인 (없는 id 면 404). 보내지 않았으면 그대로 둔다
        Optional<Album> album = cmd.albumId() == null ? null : cmd.albumId().map(albumId -> albums.findById(albumId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.ALBUM_NOT_FOUND)));

        // 3. 별칭 충돌은 변경 전에 검사한다. 별칭은 전체 곡에서 유일하다
        if (aliases != null) {
            rejectAliasesOwnedByOthers(aliases, existing.map(Song::getId).orElse(null));
        }

        // 4. 보낸 필드만 반영한다. 제목을 보내면 원어와 번역 전체를 교체한다
        Song song = existing.orElseGet(() -> new Song(cmd.title().original().trim(), DEFAULT_LOCALE));
        if (cmd.title() != null) {
            song.rename(cmd.title().original().trim(), requestedLocale == null ? song.getOriginalLocale() : requestedLocale);
            song.replaceTranslations(tagMap(cmd.title()), TranslationSource.ADMIN);
        } else if (requestedLocale != null) {
            song.rename(song.getTitle(), requestedLocale);
        }
        if (aliases != null) {
            song.replaceAliases(aliases);
        }
        if (cmd.itunesTrackId() != null) {
            song.changeItunesTrackId(cmd.itunesTrackId().orElse(null));
        }
        if (cmd.youtubeVideoId() != null) {
            song.changeYoutubeVideoId(cmd.youtubeVideoId().map(CatalogCommand::blankToNull).orElse(null));
        }
        if (album != null) {
            song.changeAlbum(album.orElse(null));
        }
        if (cmd.selectable() != null) {
            song.changeSelectable(cmd.selectable());
        }
        Song saved = songs.save(song);
        log.info("[곡 저장] songId={}, 신규={}, 별칭수={}, albumId={}, selectable={}", saved.getId(), existing.isEmpty(),
                saved.getAliases().size(), saved.getAlbum() == null ? null : saved.getAlbum().getId(), saved.isSelectable());
        return query.findSong(saved.getId()).orElseThrow();
    }

    // 앨범 저장 (병합). 곡 저장과 같은 규칙으로 보낸 값만 반영한다. 커버는 uploadCover 로만 바뀐다
    @Transactional
    public AlbumSnapshot upsertAlbum(UpsertAlbum cmd) {
        String requestedLocale = cmd.originalLocale() == null ? null : locale(cmd.originalLocale());
        Optional<Album> existing = cmd.id() != null
                ? Optional.of(albums.findById(cmd.id()).orElseThrow(() -> new NotFoundException(ErrorCode.ALBUM_NOT_FOUND)))
                : Optional.ofNullable(cmd.title())
                        .flatMap(title -> koreanTitle(title, requestedLocale == null ? DEFAULT_LOCALE : requestedLocale))
                        .flatMap(title -> single(albums.findByKoreanTitle(title), title));
        if (existing.isEmpty() && cmd.title() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "새 앨범에는 title 이 필요합니다.");
        }
        if (cmd.title() != null) {
            requireValidTitle(cmd.title());
        }
        Album album = existing.orElseGet(() -> new Album(cmd.title().original().trim(), DEFAULT_LOCALE));
        if (cmd.title() != null) {
            album.rename(cmd.title().original().trim(), requestedLocale == null ? album.getOriginalLocale() : requestedLocale);
            album.replaceTranslations(tagMap(cmd.title()), TranslationSource.ADMIN);
        } else if (requestedLocale != null) {
            album.rename(album.getTitle(), requestedLocale);
        }
        if (cmd.releaseDate() != null) {
            album.changeReleaseDate(cmd.releaseDate().orElse(null));
        }
        if (cmd.itunesCollectionId() != null) {
            album.changeItunesCollectionId(cmd.itunesCollectionId().orElse(null));
        }
        Album saved = albums.save(album);
        log.info("[앨범 저장] albumId={}, 신규={}", saved.getId(), existing.isEmpty());
        return query.toSnapshot(saved);
    }

    /**
     * 엑셀 import. 한국어 제목이 빈 행은 skipped, 행 단위 오류(iTunes ID 숫자 아님, 별칭 충돌 등)는 errors 에 남기고 계속한다.
     * 곡 저장과 같은 병합 규칙이며 빈 칸 = 키 없음(유지)이다. 시트에는 지우는 표현이 없으므로 import 는 값을 지우지 않는다:
     * 빈 번역·ID·앨범 칸은 기존 값을 두고, 별칭은 기존 별칭에 더한다(미매칭 연결로 붙은 별칭이 다시 올린 시트에 없어도 남는다).
     * 시트에 없는 선택 가능 여부와 커버는 건드리지 않는다. 오류 행 외에는 저장되므로 같은 파일을 다시 올리면 updated 로 집계된다.
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
                        : single(albums.findByKoreanTitle(row.albumTitle().trim()), row.albumTitle().trim())
                                .orElseGet(() -> albums.save(new Album(row.albumTitle().trim(), DEFAULT_LOCALE)));
                // 2. 곡: 한국어 제목 일치 여부로 created/updated 집계. 번역·별칭은 기존 값 위에 비어 있지 않은 칸을 얹는다
                Optional<Song> existing = single(songs.findByKoreanTitle(row.ko().trim()), row.ko().trim());
                Map<SupportedLocale, String> translations = new EnumMap<>(SupportedLocale.class);
                existing.ifPresent(song -> song.getTranslations().forEach(t -> SupportedLocale.fromTag(t.getLocale())
                        .ifPresent(locale -> translations.put(locale, t.getText()))));
                putIfPresent(translations, SupportedLocale.EN, row.en());
                putIfPresent(translations, SupportedLocale.ZH_TW, row.zhTw());
                putIfPresent(translations, SupportedLocale.JA, row.ja());
                List<String> aliases = new ArrayList<>(existing.map(Song::aliasValues).orElse(List.of()));
                if (row.aliases() != null) {
                    Arrays.stream(row.aliases().split("\\|")).map(String::trim).filter(s -> !s.isEmpty()).forEach(aliases::add);
                }
                Long itunesTrackId = parseLong(row.itunesTrackId());
                upsertSong(new UpsertSong(existing.map(Song::getId).orElse(null),
                        new LocalizedText(row.ko().trim(), translations), DEFAULT_LOCALE, Optional.of(aliases),
                        itunesTrackId == null ? null : Optional.of(itunesTrackId),
                        blankToNull(row.youtubeVideoId()) == null ? null : Optional.of(row.youtubeVideoId().trim()),
                        album == null ? null : Optional.of(album.getId()), null));
                if (existing.isPresent()) {
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

    // 빈 문자열은 ko 로 본다 (생략과 같다)
    private static String locale(String tag) {
        if (tag.isBlank()) {
            return DEFAULT_LOCALE;
        }
        return SupportedLocale.fromTag(tag)
                .map(SupportedLocale::tag)
                .orElseThrow(() -> new BadRequestException(ErrorCode.INVALID_INPUT, "지원하지 않는 로케일입니다: " + tag));
    }

    // 한국어 제목 = 원어가 ko 면 원어, 아니면 ko 번역
    // 한 곡의 원어 제목이 다른 곡의 ko 번역과 같으면 어느 쪽을 갱신할지 정할 수 없다. 예외를 저장소 밖에서 던져 import 는 그 행만 실패한다
    private static <T> Optional<T> single(List<T> found, String koreanTitle) {
        if (found.size() > 1) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "한국어 제목 '" + koreanTitle + "' 에 해당하는 항목이 " + found.size() + "개라 대상을 정할 수 없습니다. id 로 지정해 주세요.");
        }
        return found.stream().findFirst();
    }

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

    // 원어는 비어 있으면 안 되고, 원어·번역 모두 컬럼 길이(255) 이하여야 한다
    private static void requireValidTitle(LocalizedText title) {
        if (title.original().isBlank()) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "title.original 이 비어 있습니다.");
        }
        requireMaxLength("title.original", title.original().trim(), TEXT_MAX);
        title.translations().forEach((locale, text) -> requireMaxLength("title." + locale.tag(), text.trim(), TEXT_MAX));
    }

    private static void requireValidAliases(List<String> aliases) {
        if (aliases.size() > ALIASES_MAX) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "별칭은 " + ALIASES_MAX + "개 이하여야 합니다.");
        }
        aliases.forEach(alias -> requireMaxLength("aliases", AliasNormalizer.normalize(alias), TEXT_MAX));
    }

    private static void requireMaxLength(String field, String value, int max) {
        if (value.length() > max) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, field + " 는 " + max + "자 이하여야 합니다.");
        }
    }

    private static void putIfPresent(Map<SupportedLocale, String> translations, SupportedLocale locale, String cell) {
        String text = blankToNull(cell);
        if (text != null) {
            translations.put(locale, text);
        }
    }

    private static Long parseLong(String raw) {
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
