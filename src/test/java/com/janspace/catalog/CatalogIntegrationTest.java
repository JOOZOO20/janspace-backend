package com.janspace.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.support.IntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
 * 곡 upsert·검색·번역 유일성, 엑셀 import 2회, 커버 업로드 -> coverUrl, 관리자 API 401 을 실제 MySQL 로 검증한다.
 * 테스트 간 곡 제목을 다르게 두어 순서에 의존하지 않는다.
 */
@AutoConfigureMockMvc
class CatalogIntegrationTest extends IntegrationTest {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("곡 upsert 후 원어·번역·별칭으로 검색되고, 같은 한국어 제목으로 다시 올리면 같은 곡이 갱신되며, 번역 (song, field, locale) 은 유일하다")
    void upsert_search_translations_unique() throws Exception {
        String body = """
                {"title": {"original": "주저하는 연인들을 위해", "en": "For Lovers Who Hesitate", "ja": "ためらう恋人たちのために"},
                 "aliases": ["  For Lovers Who Hesitate ", "for lovers"], "itunesId": 1456790871, "youtubeId": "yt123"}""";
        DocumentContext created = json(mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK")).andReturn());
        long id = created.read("$.data.id", Long.class);
        assertThat(created.read("$.data.title.ko", String.class)).isEqualTo("주저하는 연인들을 위해");
        assertThat(created.read("$.data.title.en", String.class)).isEqualTo("For Lovers Who Hesitate");
        assertThat(created.read("$.data.title.ja", String.class)).isEqualTo("ためらう恋人たちのために");
        assertThat(created.read("$.data.aliases", List.class)).containsExactlyInAnyOrder("for lovers who hesitate", "for lovers");
        assertThat(created.read("$.data.itunesId", Long.class)).isEqualTo(1456790871L);
        assertThat(created.read("$.data.youtubeId", String.class)).isEqualTo("yt123");

        // 원어·번역·별칭 어디로든 대소문자 무시 검색
        for (String q : List.of("주저하는", "hesitate", "FOR LOVERS")) {
            DocumentContext found = json(mvc.perform(get("/api/v1/songs").param("q", q)).andExpect(status().isOk()).andReturn());
            assertThat(found.read("$.data[*].id", List.class)).as("q=%s", q).contains((int) id);
        }

        // id 없이 같은 한국어 제목 -> 갱신 (별칭 교체)
        String again = """
                {"title": {"original": "주저하는 연인들을 위해", "en": "For Lovers Who Hesitate (2nd)"}, "aliases": ["for lovers who hesitate"]}""";
        DocumentContext updated = json(mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content(again))
                .andExpect(status().isOk()).andReturn());
        assertThat(updated.read("$.data.id", Long.class)).isEqualTo(id);
        assertThat(updated.read("$.data.title.en", String.class)).isEqualTo("For Lovers Who Hesitate (2nd)");
        assertThat(updated.read("$.data.aliases", List.class)).containsExactly("for lovers who hesitate");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM song_translations WHERE song_id = ?", Integer.class, id)).isEqualTo(1);

        // DB 유일 제약: (song_id, field, locale) 중복 INSERT 거부
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO song_translations (song_id, field, locale, text, source, reviewed, created_at, updated_at)
                VALUES (?, 'TITLE', 'en', 'dup', 'ADMIN', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""", id))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 다른 곡이 이미 가진 별칭은 409
        String conflict = """
                {"title": {"original": "다른 곡"}, "aliases": ["For Lovers Who Hesitate"]}""";
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON).content(conflict))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("엑셀 import 는 앨범·곡을 만들고, 같은 파일을 다시 올리면 updated 로 집계된다")
    void import_creates_then_updates() throws Exception {
        byte[] xlsx = SongSheetFixture.xlsx(true,
                new String[] {"뜨거운 여름밤은 가고 남은 건 볼품없지만", "Summer", "夏", "夏", "summer|hot summer night", "1456790869", "yt1", "전설(import)"},
                new String[] {"악몽(import)", "Bad Dreams", null, null, "bad dreams", null, null, "전설(import)"},
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
        assertThat(albums.read("$.data[*].title.ko", List.class)).contains("전설(import)");
        DocumentContext songs = json(mvc.perform(get("/api/v1/songs").param("q", "(import)")).andExpect(status().isOk()).andReturn());
        assertThat(songs.read("$.data[*].title.ko", List.class)).containsExactlyInAnyOrder("악몽(import)");
        DocumentContext summer = json(mvc.perform(get("/api/v1/songs").param("q", "hot summer")).andExpect(status().isOk()).andReturn());
        assertThat(summer.read("$.data[0].itunesId", Long.class)).isEqualTo(1456790869L);
        assertThat(summer.read("$.data[0].albumId", Long.class)).isNotNull();

        DocumentContext second = json(mvc.perform(multipart(HttpMethod.POST, "/api/v1/admin/songs/import")
                        .file(new MockMultipartFile("file", "songs.xlsx", XLSX, xlsx)))
                .andExpect(status().isOk()).andReturn());
        assertThat(second.read("$.data.created", Integer.class)).isEqualTo(0);
        assertThat(second.read("$.data.updated", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM albums WHERE title = '전설(import)'", Integer.class)).isEqualTo(1);
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("커버 업로드는 S3 에 albums/{id}.png 로 올리고 cover_key 를 저장하며 GET /songs 의 coverUrl 로 나온다")
    void cover_upload_flows_to_song_cover_url() throws Exception {
        DocumentContext album = json(mvc.perform(put("/api/v1/admin/albums").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": {"original": "MONKEY HOTEL(cover)", "en": "Monkey Hotel"}, "releaseDate": "2016-08-04", "itunesCollectionId": 1163370710}"""))
                .andExpect(status().isOk()).andReturn());
        long albumId = album.read("$.data.id", Long.class);
        assertThat(album.read("$.data.releaseDate", String.class)).isEqualTo("2016-08-04");
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
                        .content("{\"title\": {\"original\": \"She(cover)\"}, \"albumId\": " + albumId + "}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(song.read("$.data.albumId", Long.class)).isEqualTo(albumId);
        DocumentContext listed = json(mvc.perform(get("/api/v1/songs").param("q", "she(cover)")).andExpect(status().isOk()).andReturn());
        assertThat(listed.read("$.data[0].coverUrl", String.class)).endsWith("/albums/" + albumId + ".png");

        // 없는 앨범 -> 404 ALBUM_NOT_FOUND
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": {\"original\": \"x\"}, \"albumId\": 999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ALBUM_NOT_FOUND"));
        mvc.perform(put("/api/v1/admin/songs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": 999999, \"title\": {\"original\": \"x\"}}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
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

    private static DocumentContext json(MvcResult result) throws Exception {
        return JsonPath.parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
