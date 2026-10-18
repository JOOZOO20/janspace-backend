package com.janspace.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.janspace.catalog.application.port.CoverStoragePort;
import com.janspace.catalog.domain.AlbumRepository;
import com.janspace.catalog.domain.Song;
import com.janspace.catalog.domain.SongRepository;
import com.janspace.catalog.domain.TranslationSource;
import com.janspace.common.locale.SupportedLocale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * matchSetlistFmName 정책을 메모리 데이터로 검증한다: 별칭 정확 일치 -> 제목·번역 정규화 일치 -> empty.
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
        assertThat(query.matchSetlistFmName("  \"For Lovers Who Hesitate\" ")).contains(1L);
    }

    @Test
    @DisplayName("별칭이 없어도 원어 제목이 대소문자 무시로 일치하면 그 곡")
    void title_match_is_case_insensitive() {
        assertThat(query.matchSetlistFmName("legend")).contains(2L);
        assertThat(query.matchSetlistFmName("Legend")).contains(2L);
    }

    @Test
    @DisplayName("번역 제목으로도 일치한다")
    void translation_match() {
        assertThat(query.matchSetlistFmName("bad dreams")).contains(3L);
    }

    @Test
    @DisplayName("어디에도 없으면 empty")
    void unknown_is_empty() {
        assertThat(query.matchSetlistFmName("unknown song")).isEmpty();
        assertThat(query.matchSetlistFmName("   ")).isEmpty();
        assertThat(query.matchSetlistFmName(null)).isEmpty();
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

    static Song song(Long id, String title, Map<String, String> translations, List<String> aliases) {
        Song song = new Song(title, "ko");
        ReflectionTestUtils.setField(song, "id", id);
        song.replaceTranslations(translations, TranslationSource.ADMIN);
        song.replaceAliases(aliases);
        return song;
    }
}
