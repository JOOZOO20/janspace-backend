package com.stagelog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.support.IntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 곡 저장·검색·번역 유일성, 저장 병합(키 없음 = 유지, null = 지움), 선택 가능 필터, 엑셀 import(빈 칸 유지·별칭 추가),
 * 커버 업로드 -> coverUrl·albumTitle, 관리자 API 401 을 실제 MySQL 로 검증한다.
 * 테스트 간 곡 제목을 다르게 두어 순서에 의존하지 않는다.
 */
@AutoConfigureMockMvc
class CatalogIntegrationTest extends IntegrationTest {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("잘못된 iTunes ID 행은 저장하지 않고 뒤의 정상 행은 커밋")
    void invalid_import_row_leaves_no_album() throws Exception {
        byte[] xlsx = SongSheetFixture.xlsx(true,
                new String[] {"행 격리 숫자 오류", null, null, null, null, "not-a-number", null, "실패 숫자 앨범"},
                new String[] {"행 격리 정상 곡", null, null, null, null, null, null, "행 격리 정상 앨범"});

        mvc.perform(multipart("/api/v1/admin/songs/import").file(new MockMultipartFile("file", "songs.xlsx", XLSX, xlsx)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.errors.length()").value(1));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM albums WHERE title = '실패 숫자 앨범'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM songs WHERE title = '행 격리 정상 곡'", Integer.class)).isEqualTo(1);
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("앨범 생성 뒤 별칭 충돌이 발생해도 실패 행 전체 롤백 및 다음 행 저장")
    void alias_conflict_rolls_back_album_and_continues_import() throws Exception {
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":{\"original\":\"행 격리 별칭 소유곡\"},\"aliases\":[\"row-rollback-alias\"]}"))
                .andExpect(status().isOk());
        byte[] xlsx = SongSheetFixture.xlsx(true,
                new String[] {"행 격리 별칭 오류", null, null, null, "row-rollback-alias", null, null, "실패 별칭 앨범"},
                new String[] {"별칭 오류 뒤 정상 곡", null, null, null, null, null, null, "별칭 오류 뒤 정상 앨범"});

        mvc.perform(multipart("/api/v1/admin/songs/import").file(new MockMultipartFile("file", "songs.xlsx", XLSX, xlsx)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.errors.length()").value(1));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM albums WHERE title = '실패 별칭 앨범'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM songs WHERE title = '행 격리 별칭 오류'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM songs WHERE title = '별칭 오류 뒤 정상 곡'", Integer.class)).isEqualTo(1);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("곡 upsert 후 원어·번역·별칭으로 검색되고, 같은 한국어 제목으로 다시 올리면 같은 곡이 갱신되며, 번역 (song, field, locale) 은 유일하다")
    void upsert_search_translations_unique() throws Exception {
        String body = """
                {"title": {"original": "떠도는 별들을 위해", "en": "For Stars Who Wander", "ja": "さまよう星たちのために"},
                 "aliases": ["  For Stars Who Wander ", "for stars"], "itunesId": 1900000101, "youtubeId": "yt123"}""";
        DocumentContext created = json(mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK")).andReturn());
        long id = created.read("$.data.id", Long.class);
        assertThat(created.read("$.data.title.ko", String.class)).isEqualTo("떠도는 별들을 위해");
        assertThat(created.read("$.data.title.en", String.class)).isEqualTo("For Stars Who Wander");
        assertThat(created.read("$.data.title.ja", String.class)).isEqualTo("さまよう星たちのために");
        assertThat(created.read("$.data.aliases", List.class)).containsExactlyInAnyOrder("for stars who wander", "for stars");
        assertThat(created.read("$.data.itunesId", Long.class)).isEqualTo(1900000101L);
        assertThat(created.read("$.data.youtubeId", String.class)).isEqualTo("yt123");

        // 원어·번역·별칭 어디로든 대소문자 무시 검색
        for (String q : List.of("떠도는", "wander", "FOR STARS")) {
            DocumentContext found = json(mvc.perform(get("/api/v1/songs").param("q", q)).andExpect(status().isOk()).andReturn());
            assertThat(found.read("$.data[*].id", List.class)).as("q=%s", q).contains((int) id);
        }

        // id 없이 같은 한국어 제목 -> 갱신 (별칭 교체)
        String again = """
                {"title": {"original": "떠도는 별들을 위해", "en": "For Stars Who Wander (2nd)"}, "aliases": ["for stars who wander"]}""";
        DocumentContext updated = json(mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content(again))
                .andExpect(status().isOk()).andReturn());
        assertThat(updated.read("$.data.id", Long.class)).isEqualTo(id);
        assertThat(updated.read("$.data.title.en", String.class)).isEqualTo("For Stars Who Wander (2nd)");
        assertThat(updated.read("$.data.aliases", List.class)).containsExactly("for stars who wander");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM song_translations WHERE song_id = ?", Integer.class, id)).isEqualTo(1);

        // DB 유일 제약: (song_id, field, locale) 중복 INSERT 거부
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO song_translations (song_id, field, locale, text, source, reviewed, created_at, updated_at)
                VALUES (?, 'TITLE', 'en', 'dup', 'ADMIN', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 다른 곡이 이미 가진 별칭은 409
        String conflict = """
                {"title": {"original": "다른 곡"}, "aliases": ["For Stars Who Wander"]}""";
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("엑셀 import 는 앨범·곡을 만들고, 같은 파일을 다시 올리면 updated 로 집계된다")
    void import_creates_then_updates() throws Exception {
        byte[] xlsx = SongSheetFixture.xlsx(true,
                new String[] {"긴 겨울밤이 지나고 남은 건 서툰 노래뿐이지만", "Winter", "冬", "冬", "winter|long winter night", "1900000102", "yt1", "등대(import)"},
                new String[] {"백일몽(import)", "Daydreams", null, null, "daydreams import", null, null, "등대(import)"},
                new String[] {"", null, null, null, null, null, null, null},
                new String[] {"숫자오류(import)", null, null, null, null, "abc", null, null});

        DocumentContext first = json(mvc.perform(multipart(HttpMethod.POST, "/api/v1/admin/songs/import")
                        .file(new MockMultipartFile("file", "songs.xlsx", XLSX, xlsx)))
                .andExpect(status().isOk()).andReturn());
        assertThat(first.read("$.data.created", Integer.class)).isEqualTo(2);
        assertThat(first.read("$.data.updated", Integer.class)).isEqualTo(0);
        assertThat(first.read("$.data.skipped", Integer.class)).isEqualTo(1);
        assertThat(first.read("$.data.errors", List.class)).hasSize(1);
        assertThat(first.read("$.data.errors[0]", String.class)).startsWith("5행");

        DocumentContext albums = json(mvc.perform(get("/api/v1/albums")).andExpect(status().isOk()).andReturn());
        assertThat(albums.read("$.data[*].title.ko", List.class)).contains("등대(import)");
        DocumentContext songs = json(mvc.perform(get("/api/v1/songs").param("q", "(import)")).andExpect(status().isOk()).andReturn());
        assertThat(songs.read("$.data[*].title.ko", List.class)).containsExactlyInAnyOrder("백일몽(import)");
        DocumentContext winter = json(mvc.perform(get("/api/v1/songs").param("q", "long winter")).andExpect(status().isOk()).andReturn());
        assertThat(winter.read("$.data[0].itunesId", Long.class)).isEqualTo(1900000102L);
        assertThat(winter.read("$.data[0].albumId", Long.class)).isNotNull();

        DocumentContext second = json(mvc.perform(multipart(HttpMethod.POST, "/api/v1/admin/songs/import")
                        .file(new MockMultipartFile("file", "songs.xlsx", XLSX, xlsx)))
                .andExpect(status().isOk()).andReturn());
        assertThat(second.read("$.data.created", Integer.class)).isEqualTo(0);
        assertThat(second.read("$.data.updated", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM albums WHERE title = '등대(import)'", Integer.class)).isEqualTo(1);
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("한국어 제목이 한 곡의 원어 제목이자 다른 곡의 ko 번역이면 upsert 는 409, import 는 그 행만 오류로 남기고 나머지를 저장한다")
    void ambiguous_korean_title_is_conflict() throws Exception {
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content("{\"title\": {\"original\": \"두개제목(dup)\"}}"))
                .andExpect(status().isOk());
        long other = json(mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": {\"original\": \"Two Titles(dup)\"}, \"originalLocale\": \"en\"}"))
                .andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
        jdbc.update("""
                INSERT INTO song_translations (song_id, field, locale, text, source, reviewed, created_at, updated_at)
                VALUES (?, 'TITLE', 'ko', '두개제목(dup)', 'ADMIN', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", other);

        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content("{\"title\": {\"original\": \"두개제목(dup)\"}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        byte[] xlsx = SongSheetFixture.xlsx(true,
                new String[] {"두개제목(dup)", null, null, null, null, null, null, null},
                new String[] {"정상곡(dup)", null, null, null, null, null, null, null});
        DocumentContext report = json(mvc.perform(multipart(HttpMethod.POST, "/api/v1/admin/songs/import")
                        .file(new MockMultipartFile("file", "songs.xlsx", XLSX, xlsx)))
                .andExpect(status().isOk()).andReturn());
        assertThat(report.read("$.data.created", Integer.class)).isEqualTo(1);
        assertThat(report.read("$.data.errors", List.class)).hasSize(1);
        assertThat(report.read("$.data.errors[0]", String.class)).startsWith("2행");
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("검색어의 % _ 는 와일드카드가 아니라 글자로 찾는다")
    void search_escapes_like_wildcards() throws Exception {
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content("{\"title\": {\"original\": \"100%_like(esc)\"}}"))
                .andExpect(status().isOk());

        DocumentContext percent = json(mvc.perform(get("/api/v1/songs").param("q", "0%_l")).andExpect(status().isOk()).andReturn());
        assertThat(percent.read("$.data[*].title.ko", List.class)).containsExactly("100%_like(esc)");
        DocumentContext wildcard = json(mvc.perform(get("/api/v1/songs").param("q", "%")).andExpect(status().isOk()).andReturn());
        assertThat(wildcard.read("$.data[*].title.ko", List.class)).containsExactly("100%_like(esc)");
        DocumentContext underscore = json(mvc.perform(get("/api/v1/songs").param("q", "1_0")).andExpect(status().isOk()).andReturn());
        assertThat(underscore.read("$.data", List.class)).isEmpty();
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("커버 업로드는 S3 에 albums/{id}.png 로 올리고 cover_key 를 저장하며 GET /songs 의 coverUrl 로 나온다")
    void cover_upload_flows_to_song_cover_url() throws Exception {
        DocumentContext album = json(mvc.perform(put("/api/v1/admin/albums").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": {"original": "MOON MOTEL(cover)", "en": "Moon Motel"}, "releaseDate": "2017-04-12", "itunesCollectionId": 1900000200}"""))
                .andExpect(status().isOk()).andReturn());
        long albumId = album.read("$.data.id", Long.class);
        assertThat(album.read("$.data.releaseDate", String.class)).isEqualTo("2017-04-12");
        assertThat(album.read("$.data.coverUrl", String.class)).isNull();

        DocumentContext cover = json(mvc.perform(multipart(HttpMethod.PUT, "/api/v1/admin/albums/" + albumId + "/cover")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", PNG)))
                .andExpect(status().isOk()).andReturn());
        assertThat(cover.read("$.data.coverUrl", String.class)).endsWith("/albums/" + albumId + ".png");
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().key()).isEqualTo("albums/" + albumId + ".png");
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
        assertThat(jdbc.queryForObject("SELECT cover_key FROM albums WHERE id = ?", String.class, albumId))
                .isEqualTo("albums/" + albumId + ".png");

        // PNG/JPEG 가 아니면 400
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/admin/albums/" + albumId + "/cover")
                        .file(new MockMultipartFile("file", "cover.png", "image/png", "not image".getBytes())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COVER_INVALID"));

        // 곡에 앨범을 붙이면 coverUrl 이 따라온다
        DocumentContext song = json(mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": {\"original\": \"Comet(cover)\"}, \"albumId\": " + albumId + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(song.read("$.data.albumId", Long.class)).isEqualTo(albumId);
        DocumentContext listed = json(mvc.perform(get("/api/v1/songs").param("q", "comet(cover)")).andExpect(status().isOk()).andReturn());
        assertThat(listed.read("$.data[0].coverUrl", String.class)).endsWith("/albums/" + albumId + ".png");
        assertThat(listed.read("$.data[0].albumTitle.original", String.class)).isEqualTo("MOON MOTEL(cover)");
        assertThat(listed.read("$.data[0].albumTitle.en", String.class)).isEqualTo("Moon Motel");
        assertThat(listed.read("$.data[0].selectable", Boolean.class)).isTrue();

        // 없는 앨범 -> 404 ALBUM_NOT_FOUND
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": {\"original\": \"x\"}, \"albumId\": 999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ALBUM_NOT_FOUND"));
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": 999999, \"title\": {\"original\": \"x\"}}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("곡 저장은 보낸 키만 반영한다: 폼이 보내지 않는 albumId·selectable 은 유지되고, null 은 지우며, albumId null 은 앨범 연결을 끊는다")
    void upsert_merges_only_sent_keys() throws Exception {
        long albumId = json(mvc.perform(put("/api/v1/admin/albums").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": {\"original\": \"등대(merge)\", \"en\": \"Lighthouse(merge)\"}}"))
                .andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
        DocumentContext created = putSong("""
                {"title": {"original": "목격자(merge)"}, "aliases": ["witness merge"], "itunesId": 111, "youtubeId": "yt-merge",
                 "albumId": %d, "selectable": false}""".formatted(albumId));
        long id = created.read("$.data.id", Long.class);
        assertThat(created.read("$.data.selectable", Boolean.class)).isFalse();
        assertThat(created.read("$.data.albumTitle.en", String.class)).isEqualTo("Lighthouse(merge)");

        // 1. 관리자 폼 저장: albumId·selectable 키 없음 -> 유지, itunesId null -> 지움, title -> 교체
        DocumentContext form = putSong("""
                {"id": %d, "title": {"original": "목격자(merge)", "en": "Witness"}, "aliases": ["witness merge"],
                 "itunesId": null, "youtubeId": "yt-merge"}""".formatted(id));
        assertThat(form.read("$.data.albumId", Long.class)).isEqualTo(albumId);
        assertThat(form.read("$.data.albumTitle.ko", String.class)).isEqualTo("등대(merge)");
        assertThat(form.read("$.data.selectable", Boolean.class)).isFalse();
        assertThat(form.read("$.data.itunesId", Long.class)).isNull();
        assertThat(form.read("$.data.title.en", String.class)).isEqualTo("Witness");

        // 2. id 와 selectable 만: 제목·별칭·유튜브·앨범 유지
        DocumentContext toggled = putSong("{\"id\": %d, \"selectable\": true}".formatted(id));
        assertThat(toggled.read("$.data.selectable", Boolean.class)).isTrue();
        assertThat(toggled.read("$.data.title.en", String.class)).isEqualTo("Witness");
        assertThat(toggled.read("$.data.aliases", List.class)).containsExactly("witness merge");
        assertThat(toggled.read("$.data.youtubeId", String.class)).isEqualTo("yt-merge");
        assertThat(toggled.read("$.data.albumId", Long.class)).isEqualTo(albumId);

        // 3. albumId null -> 앨범 연결 해제(앨범 제목·커버도 빠짐), aliases null -> 모두 지움
        DocumentContext unlinked = putSong("{\"id\": %d, \"albumId\": null, \"aliases\": null}".formatted(id));
        assertThat(unlinked.read("$.data.albumId", Long.class)).isNull();
        assertThat(unlinked.read("$.data.albumTitle", Map.class)).isNull();
        assertThat(unlinked.read("$.data.coverUrl", String.class)).isNull();
        assertThat(unlinked.read("$.data.aliases", List.class)).isEmpty();
        assertThat(unlinked.read("$.data.youtubeId", String.class)).isEqualTo("yt-merge");

        // 4. 새 곡인데 title 이 없으면 400, 컬럼 길이를 넘는 유튜브 ID·original 없는 제목·숫자가 아닌 albumId 는 400
        for (String bad : List.of("{\"title\": {\"ko\": \"original 없음\"}}", "{\"id\": %d, \"albumId\": \"abc\"}".formatted(id))) {
            mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content("{\"selectable\": false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": %d, \"youtubeId\": \"123456789012345678901\"}".formatted(id)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("selectable=true 는 선택 가능 곡만, false 는 선택 제외 곡만, 생략은 전체를 돌려주며 q 가 비어도 같은 필터가 걸린다")
    void search_filters_by_selectable() throws Exception {
        long open = putSong("{\"title\": {\"original\": \"선택곡(sel)\"}, \"aliases\": [\"pick me sel\"]}").read("$.data.id", Long.class);
        long hidden = putSong("{\"title\": {\"original\": \"제외곡(sel)\"}, \"selectable\": false}").read("$.data.id", Long.class);

        assertThat(songIds("q", "(sel)")).containsExactly(open, hidden);
        assertThat(songIds("q", "(sel)", "selectable", "true")).containsExactly(open);
        assertThat(songIds("q", "(sel)", "selectable", "false")).containsExactly(hidden);
        // 빈 q: 텍스트 조건 없이 선택 가능한 곡 전부
        assertThat(songIds("q", "", "selectable", "true")).contains(open).doesNotContain(hidden);
        // 파라미터 없음: 기존 기록·셋리 표시용 전체 (선택 제외 곡 포함)
        assertThat(songIds()).contains(open, hidden);
        // 별칭 검색은 그대로
        assertThat(songIds("q", "PICK ME", "selectable", "true")).containsExactly(open);
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("엑셀 import 는 빈 칸으로 값을 지우지 않고 별칭은 더하며, 시트에 없는 선택 가능 여부는 유지한다")
    void import_keeps_values_for_empty_cells() throws Exception {
        importSheet(SongSheetFixture.xlsx(true,
                new String[] {"새벽 정류장(keep)", "Dawn Station", null, "夜明けの駅", "winter ii keep", "222", "yt-keep", "유리의 정원(keep)"}));
        long id = json(mvc.perform(get("/api/v1/songs").param("q", "새벽 정류장(keep)")).andReturn()).read("$.data[0].id", Long.class);
        // 미매칭 연결처럼 시트에 없는 별칭이 붙고, 관리자가 선택에서 뺐다
        putSong("{\"id\": %d, \"aliases\": [\"winter ii keep\", \"dawn station live keep\"], \"selectable\": false}".formatted(id));

        DocumentContext report = importSheet(SongSheetFixture.xlsx(true,
                new String[] {"새벽 정류장(keep)", null, null, null, "station keep", null, null, null}));
        assertThat(report.read("$.data.updated", Integer.class)).isEqualTo(1);

        DocumentContext song = json(mvc.perform(get("/api/v1/songs").param("q", "새벽 정류장(keep)")).andReturn());
        assertThat(song.read("$.data[0].id", Long.class)).isEqualTo(id);
        assertThat(song.read("$.data[0].title.en", String.class)).isEqualTo("Dawn Station");
        assertThat(song.read("$.data[0].title.ja", String.class)).isEqualTo("夜明けの駅");
        assertThat(song.read("$.data[0].itunesId", Long.class)).isEqualTo(222L);
        assertThat(song.read("$.data[0].youtubeId", String.class)).isEqualTo("yt-keep");
        assertThat(song.read("$.data[0].albumTitle.ko", String.class)).isEqualTo("유리의 정원(keep)");
        assertThat(song.read("$.data[0].selectable", Boolean.class)).isFalse();
        assertThat(song.read("$.data[0].aliases", List.class))
                .containsExactlyInAnyOrder("winter ii keep", "dawn station live keep", "station keep");
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("앨범 저장도 보낸 키만 반영한다: 제목만 바꾸면 발매일·iTunes ID 는 유지되고 null 을 보내면 지운다")
    void album_upsert_merges_only_sent_keys() throws Exception {
        String create = """
                {"title": {"original": "소품집(merge)"}, "releaseDate": "2017-04-12", "itunesCollectionId": 777}""";
        long id = json(mvc.perform(put("/api/v1/admin/albums").contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);

        DocumentContext renamed = json(mvc.perform(put("/api/v1/admin/albums").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": %d, \"title\": {\"original\": \"소품집 I(merge)\", \"en\": \"Tiny Orbits I\"}}".formatted(id)))
                .andExpect(status().isOk()).andReturn());
        assertThat(renamed.read("$.data.title.en", String.class)).isEqualTo("Tiny Orbits I");
        assertThat(renamed.read("$.data.releaseDate", String.class)).isEqualTo("2017-04-12");
        assertThat(renamed.read("$.data.itunesCollectionId", Long.class)).isEqualTo(777L);

        DocumentContext cleared = json(mvc.perform(put("/api/v1/admin/albums").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": %d, \"releaseDate\": null}".formatted(id)))
                .andExpect(status().isOk()).andReturn());
        assertThat(cleared.read("$.data.releaseDate", String.class)).isNull();
        assertThat(cleared.read("$.data.title.original", String.class)).isEqualTo("소품집 I(merge)");
        assertThat(cleared.read("$.data.itunesCollectionId", Long.class)).isEqualTo(777L);
    }

    @Test
    @DisplayName("관리자 쓰기 API 는 인증 없이 401 봉투")
    void admin_writes_require_auth() throws Exception {
        List<RequestBuilder> requests = List.of(
                put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content("{\"title\":{\"original\":\"x\"}}"),
                put("/api/v1/admin/albums").contentType(MediaType.APPLICATION_JSON).content("{\"title\":{\"original\":\"x\"}}"),
                multipart(HttpMethod.POST, "/api/v1/admin/songs/import").file(new MockMultipartFile("file", "a.xlsx", XLSX, new byte[0])),
                multipart(HttpMethod.PUT, "/api/v1/admin/albums/1/cover").file(new MockMultipartFile("file", "a.png", "image/png", PNG)));
        for (RequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    private DocumentContext putSong(String body) throws Exception {
        return json(mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn());
    }

    private DocumentContext importSheet(byte[] xlsx) throws Exception {
        return json(mvc.perform(multipart(HttpMethod.POST, "/api/v1/admin/songs/import")
                        .file(new MockMultipartFile("file", "songs.xlsx", XLSX, xlsx)))
                .andExpect(status().isOk()).andReturn());
    }

    // 쿼리 파라미터를 이름, 값 순서로 받아 GET /songs 결과의 id 목록을 돌려준다
    private List<Long> songIds(String... params) throws Exception {
        var request = get("/api/v1/songs");
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        List<Number> ids = json(mvc.perform(request).andExpect(status().isOk()).andReturn()).read("$.data[*].id", List.class);
        return ids.stream().map(Number::longValue).toList();
    }

    private static DocumentContext json(MvcResult result) throws Exception {
        return JsonPath.parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
