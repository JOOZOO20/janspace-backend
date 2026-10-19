package com.stagelog.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stagelog.catalog.application.port.CoverStoragePort;
import com.stagelog.catalog.domain.Album;
import com.stagelog.catalog.domain.AlbumRepository;
import com.stagelog.catalog.domain.Song;
import com.stagelog.catalog.domain.SongRepository;
import com.stagelog.catalog.domain.TranslationSource;
import com.stagelog.common.locale.SupportedLocale;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * matchSetlistFmNames 정책(별칭 정확 일치 -> 제목·번역 정규화 일치 -> empty), 스냅샷 매핑, q 없는 목록의 선택 가능 필터를
 * 메모리 데이터로 검증한다.
 */
class CatalogQueryTest {

    SongRepository songs = mock(SongRepository.class);
    CatalogQuery query = new CatalogQuery(songs, mock(AlbumRepository.class), mock(CoverStoragePort.class));

    @BeforeEach
    void setUp() {
        Song lovers = song(1L, "주저하는 연인들을 위해", Map.of("en", "For Lovers Who Hesitate"), List.of("for lovers who hesitate"));
        Song legend = song(2L, "LEGEND", Map.of(), List.of());
        Song badDreams = song(3L, "악몽", Map.of("en", "Bad Dreams"), List.of());
        when(songs.findByAliases_Alias(anyString())).thenReturn(Optional.empty());
        when(songs.findByAliases_Alias("for lovers who hesitate")).thenReturn(Optional.of(lovers));
        when(songs.findAllWithDetails()).thenReturn(List.of(lovers, legend, badDreams));
    }

    @Test
    @DisplayName("setlist.fm 표기가 별칭과 정규화 후 일치하면 그 곡")
    void alias_match_after_normalization() {
        assertThat(match("  \"For Lovers Who Hesitate\" ")).contains(1L);
    }

    @Test
    @DisplayName("별칭이 없어도 원어 제목이 대소문자 무시로 일치하면 그 곡")
    void title_match_is_case_insensitive() {
        assertThat(match("legend")).contains(2L);
        assertThat(match("Legend")).contains(2L);
    }

    @Test
    @DisplayName("번역 제목으로도 일치한다")
    void translation_match() {
        assertThat(match("bad dreams")).contains(3L);
    }

    @Test
    @DisplayName("어디에도 없으면 empty")
    void unknown_is_empty() {
        assertThat(match("unknown song")).isEmpty();
        assertThat(match("   ")).isEmpty();
        assertThat(match(null)).isEmpty();
    }

    @Test
    @DisplayName("한 번에 여러 곡명을 맞출 때 별칭에 없는 곡명이 여럿이어도 전곡은 한 번만 읽는다")
    void loads_all_songs_once_per_batch() {
        Map<String, Long> matched = query.matchSetlistFmNames(List.of("legend", "unknown a", "bad dreams", "unknown b", "for lovers who hesitate"));

        assertThat(matched).containsOnly(Map.entry("legend", 2L), Map.entry("bad dreams", 3L), Map.entry("for lovers who hesitate", 1L));
        verify(songs, times(1)).findAllWithDetails();
    }

    private Optional<Long> match(String raw) {
        return Optional.ofNullable(query.matchSetlistFmNames(Arrays.asList(raw)).get(raw));
    }

    @Test
    @DisplayName("스냅샷 title 에는 원어 로케일 키(ko)와 번역 키가 모두 들어간다")
    void snapshot_title_contains_original_locale_key() {
        Song lovers = song(1L, "주저하는 연인들을 위해", Map.of("en", "For Lovers Who Hesitate"), List.of());
        SongSnapshot snapshot = query.toSnapshot(lovers);
        assertThat(snapshot.title().original()).isEqualTo("주저하는 연인들을 위해");
        assertThat(snapshot.title().translation(SupportedLocale.KO)).contains("주저하는 연인들을 위해");
        assertThat(snapshot.title().translation(SupportedLocale.EN)).contains("For Lovers Who Hesitate");
        assertThat(snapshot.albumId()).isNull();
        assertThat(snapshot.coverUrl()).isNull();
    }

    @Test
    @DisplayName("스냅샷의 albumTitle 은 대표 앨범의 원어·번역이고, 앨범을 끊으면 albumTitle·coverUrl 이 비며 selectable 이 그대로 나온다")
    void snapshot_album_title_and_selectable() {
        Album legend = new Album("전설", "ko");
        ReflectionTestUtils.setField(legend, "id", 9L);
        legend.replaceTranslations(Map.of("en", "Legend"), TranslationSource.ADMIN);
        Song lovers = song(1L, "주저하는 연인들을 위해", Map.of(), List.of());
        lovers.changeAlbum(legend);
        lovers.changeSelectable(false);

        SongSnapshot snapshot = query.toSnapshot(lovers);
        assertThat(snapshot.albumId()).isEqualTo(9L);
        assertThat(snapshot.albumTitle().original()).isEqualTo("전설");
        assertThat(snapshot.albumTitle().translation(SupportedLocale.KO)).contains("전설");
        assertThat(snapshot.albumTitle().translation(SupportedLocale.EN)).contains("Legend");
        assertThat(snapshot.selectable()).isFalse();

        lovers.changeAlbum(null);
        assertThat(query.toSnapshot(lovers).albumTitle()).isNull();
        assertThat(query.toSnapshot(lovers).coverUrl()).isNull();
    }

    @Test
    @DisplayName("q 없는 목록: selectable null 은 전곡, true/false 는 그 값인 곡만, limit 은 필터 뒤에 적용")
    void list_without_query_filters_selectable() {
        Song hidden = song(4L, "걱정말아요 그대", Map.of(), List.of());
        hidden.changeSelectable(false);
        Song lovers = song(1L, "주저하는 연인들을 위해", Map.of(), List.of());
        when(songs.findAllWithDetails()).thenReturn(List.of(lovers, hidden));

        assertThat(query.search(null, null, null)).extracting(SongSnapshot::id).containsExactly(1L, 4L);
        assertThat(query.search("  ", true, null)).extracting(SongSnapshot::id).containsExactly(1L);
        assertThat(query.search("", false, null)).extracting(SongSnapshot::id).containsExactly(4L);
        assertThat(query.search(null, false, 1)).extracting(SongSnapshot::id).containsExactly(4L);
    }

    static Song song(Long id, String title, Map<String, String> translations, List<String> aliases) {
        Song song = new Song(title, "ko");
        ReflectionTestUtils.setField(song, "id", id);
        song.replaceTranslations(translations, TranslationSource.ADMIN);
        song.replaceAliases(aliases);
        return song;
    }
}
