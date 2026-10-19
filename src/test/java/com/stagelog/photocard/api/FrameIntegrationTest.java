package com.stagelog.photocard.api;

import static com.stagelog.photocard.api.PhotocardIntegrationTest.admin;
import static com.stagelog.photocard.api.PhotocardIntegrationTest.create;
import static com.stagelog.photocard.api.PhotocardIntegrationTest.json;
import static com.stagelog.photocard.api.PhotocardIntegrationTest.jsonPart;
import static com.stagelog.photocard.api.PhotocardIntegrationTest.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.attendance.support.AttendanceIntegrationTest;
import com.jayway.jsonpath.DocumentContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 네컷 프레임: 관리자 upsert(SVG + 칸 좌표 JSON 을 같은 이름으로 S3 에 저장, Content-Type), 메타만 갱신·파일 교체,
 * 검증 실패 시 업로드 없음, 공개 목록 기간 필터·관리자 전체 목록, 소프트 삭제 후에도 네컷의 frameId 유지, 권한.
 */
class FrameIntegrationTest extends AttendanceIntegrationTest {

    static final long U1 = PhotocardIntegrationTest.U1;
    static final long ADMIN = PhotocardIntegrationTest.ADMIN;
    static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);
    static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1080 3240\"><rect width=\"1080\" height=\"84\" fill=\"#000\"/></svg>";
    static final String FOUR = "[{\"x\":90,\"y\":84,\"w\":900,\"h\":672},{\"x\":90,\"y\":804,\"w\":900,\"h\":672},"
            + "{\"x\":90,\"y\":1524,\"w\":900,\"h\":672},{\"x\":90,\"y\":2244,\"w\":900,\"h\":672}]";
    static final String THREE = "[{\"x\":90,\"y\":84,\"w\":900,\"h\":900},{\"x\":90,\"y\":1044,\"w\":900,\"h\":900},{\"x\":90,\"y\":2004,\"w\":900,\"h\":900}]";
    static final String UUID_RE = PhotocardIntegrationTest.UUID_RE;

    @BeforeEach
    void fixtures() {
        cleanTables();
        insertUser(U1, SEOUL);
        insertUser(ADMIN, null);
    }

    @AfterEach
    void cleanTables() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.execute("TRUNCATE TABLE photocards");
        jdbc.execute("TRUNCATE TABLE frames");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    @DisplayName("신규 upsert: frames/{uuid}.svg(image/svg+xml) 와 같은 이름의 .json(application/json, {canvas, slots}) 을 올리고 FrameSet + slotRects 를 낸다")
    void create_frame_uploads_svg_and_layout() throws Exception {
        DocumentContext created = json(mvc.perform(upsert("{\"name\":\"클린\",\"kind\":\"ORIGINAL\",\"slotRects\":" + FOUR + "}", SVG))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK")).andReturn());
        assertThat(created.read("$.data.name", String.class)).isEqualTo("클린");
        assertThat(created.read("$.data.kind", String.class)).isEqualTo("ORIGINAL");
        assertThat(created.read("$.data.slots", Integer.class)).isEqualTo(4);
        assertThat(created.read("$.data.slotRects[1].y", Integer.class)).isEqualTo(804);
        assertThat(created.read("$.data.availableFrom", Object.class)).isNull();
        String url = created.read("$.data.imageUrl", String.class);
        assertThat(url).matches("https://cdn\\.test/frames/" + UUID_RE + "\\.svg");

        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodies = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, times(2)).putObject(requests.capture(), bodies.capture());
        String svgKey = url.substring("https://cdn.test/".length());
        String jsonKey = svgKey.replaceAll("\\.svg$", ".json");
        assertThat(requests.getAllValues()).extracting(PutObjectRequest::key, PutObjectRequest::contentType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(jsonKey, "application/json"),
                        org.assertj.core.groups.Tuple.tuple(svgKey, "image/svg+xml"));
        assertThat(read(bodies.getAllValues().get(0))).isEqualTo("{\"canvas\":[1080,3240],\"slots\":" + FOUR + "}");
        assertThat(read(bodies.getAllValues().get(1))).isEqualTo(SVG);
    }

    @Test
    @DisplayName("갱신: 메타만 바꾸면 파일은 그대로(업로드 없음), 파일+칸을 함께 보내면 새 uuid 키로 교체한다")
    void update_meta_and_replace_file() throws Exception {
        long id = createFrame("{\"name\":\"우주\",\"kind\":\"ORIGINAL\",\"slotRects\":" + FOUR + "}");
        String before = json(mvc.perform(get("/api/v1/admin/frames").with(admin())).andReturn()).read("$.data[0].imageUrl", String.class);

        DocumentContext meta = json(mvc.perform(upsert("{\"id\":" + id + ",\"name\":\"10월\",\"kind\":\"SPECIAL\",\"season\":\"2026 가을\","
                        + "\"availableFrom\":\"" + TODAY_UTC + "\",\"availableTo\":\"" + TODAY_UTC.plusDays(30) + "\"}", null))
                .andExpect(status().isOk()).andReturn());
        assertThat(meta.read("$.data.id", Long.class)).isEqualTo(id);
        assertThat(meta.read("$.data.kind", String.class)).isEqualTo("SPECIAL");
        assertThat(meta.read("$.data.season", String.class)).isEqualTo("2026 가을");
        assertThat(meta.read("$.data.imageUrl", String.class)).isEqualTo(before);
        assertThat(meta.read("$.data.slots", Integer.class)).isEqualTo(4);
        verify(s3Client, times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        DocumentContext replaced = json(mvc.perform(upsert("{\"id\":" + id + ",\"name\":\"응원석\",\"kind\":\"ORIGINAL\",\"slotRects\":" + THREE + "}", SVG))
                .andExpect(status().isOk()).andReturn());
        assertThat(replaced.read("$.data.imageUrl", String.class)).isNotEqualTo(before).endsWith(".svg");
        assertThat(replaced.read("$.data.slots", Integer.class)).isEqualTo(3);
        assertThat(replaced.read("$.data.availableTo", Object.class)).isNull();
        verify(s3Client, times(4)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("검증: 신규에 파일·칸 없음, 파일만·칸만, 칸 개수·범위 오류, 기간 규칙 위반은 400 INVALID_INPUT, 위험한 SVG 는 400 FRAME_SVG_INVALID, 없는 id 는 404 — 모두 업로드 없음")
    void validation_uploads_nothing() throws Exception {
        String four = ",\"slotRects\":" + FOUR;
        mvc.perform(upsert("{\"name\":\"x\",\"kind\":\"ORIGINAL\"}", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(upsert("{\"name\":\"x\",\"kind\":\"ORIGINAL\"}", SVG)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(upsert("{\"name\":\"x\",\"kind\":\"ORIGINAL\"" + four + "}", null)).andExpect(status().isBadRequest());
        mvc.perform(upsert("{\"name\":\"x\",\"kind\":\"ORIGINAL\",\"slotRects\":[{\"x\":0,\"y\":0,\"w\":10,\"h\":10}]}", SVG))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(upsert("{\"name\":\"x\",\"kind\":\"ORIGINAL\",\"slotRects\":[{\"x\":0,\"y\":0,\"w\":10,\"h\":10},{\"x\":0,\"y\":0,\"w\":10,\"h\":10},"
                        + "{\"x\":1000,\"y\":0,\"w\":100,\"h\":10}]}", SVG))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(upsert("{\"name\":\"x\",\"kind\":\"ORIGINAL\",\"availableFrom\":\"" + TODAY_UTC + "\"" + four + "}", SVG))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(upsert("{\"name\":\"x\",\"kind\":\"SPECIAL\"" + four + "}", SVG))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(upsert("{\"name\":\" \",\"kind\":\"ORIGINAL\"" + four + "}", SVG)).andExpect(status().isBadRequest());
        mvc.perform(upsert("{\"name\":\"x\"" + four + "}", SVG)).andExpect(status().isBadRequest());
        mvc.perform(upsert("{\"name\":\"x\",\"kind\":\"ORIGINAL\"" + four + "}",
                        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1080 3240\" onload=\"alert(1)\"/>"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FRAME_SVG_INVALID"));
        mvc.perform(upsert("{\"id\":999999,\"name\":\"x\",\"kind\":\"ORIGINAL\"}", null))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FRAME_NOT_FOUND"));
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM frames", Integer.class)).isZero();
    }

    @Test
    @DisplayName("목록: 공개는 상시 + 오늘이 기간 안인 스페셜만, 관리자는 전부. 삭제는 소프트 삭제로 두 목록에서 빠지고, 그 프레임으로 만든 네컷의 frameId 는 남는다")
    void lists_and_soft_delete() throws Exception {
        long original = createFrame("{\"name\":\"클린\",\"kind\":\"ORIGINAL\",\"slotRects\":" + FOUR + "}");
        long current = createFrame(special("지금", TODAY_UTC, TODAY_UTC));
        long ended = createFrame(special("지난", TODAY_UTC.minusDays(30), TODAY_UTC.minusDays(1)));
        long future = createFrame(special("다음", TODAY_UTC.plusDays(1), TODAY_UTC.plusDays(30)));

        assertThat(json(mvc.perform(get("/api/v1/frames")).andExpect(status().isOk()).andReturn()).read("$.data[*].id", List.class))
                .containsExactly((int) original, (int) current);
        assertThat(json(mvc.perform(get("/api/v1/admin/frames").with(admin())).andExpect(status().isOk()).andReturn()).read("$.data[*].id", List.class))
                .containsExactly((int) original, (int) current, (int) ended, (int) future);

        long fourcut = json(mvc.perform(create(U1, png(10, 30), "f.png", "{\"kind\":\"FOURCUT\",\"frameId\":" + current + "}"))
                .andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
        mvc.perform(delete("/api/v1/admin/frames/{id}", current).with(admin())).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/frames/{id}", current).with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FRAME_NOT_FOUND"));
        assertThat(json(mvc.perform(get("/api/v1/frames")).andReturn()).read("$.data[*].id", List.class)).containsExactly((int) original);
        assertThat(json(mvc.perform(get("/api/v1/admin/frames").with(admin())).andReturn()).read("$.data[*].id", List.class))
                .containsExactly((int) original, (int) ended, (int) future);
        mvc.perform(get("/api/v1/photocards").with(user(U1)))
                .andExpect(jsonPath("$.data[0].id").value(fourcut)).andExpect(jsonPath("$.data[0].frameId").value(current));
        // 편집 중에 내려간 프레임으로도 완성본은 저장된다
        mvc.perform(create(U1, png(10, 30), "f.png", "{\"kind\":\"FOURCUT\",\"frameId\":" + current + "}")).andExpect(status().isOk());
        // 갱신 대상에서도 빠진다
        mvc.perform(upsert("{\"id\":" + current + ",\"name\":\"x\",\"kind\":\"ORIGINAL\"}", null)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("권한: 비관리자는 /admin/frames GET·PUT·DELETE 403, 공개 GET /frames 는 비로그인도 된다")
    void auth_rules() throws Exception {
        mvc.perform(get("/api/v1/admin/frames").with(user(U1))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/admin/frames").part(jsonPart("meta", "{\"name\":\"x\",\"kind\":\"ORIGINAL\"}")).with(user(U1)))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/frames/1").with(user(U1))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/frames")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    private long createFrame(String meta) throws Exception {
        return json(mvc.perform(upsert(meta, SVG)).andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
    }

    private static String special(String name, LocalDate from, LocalDate to) {
        return "{\"name\":\"" + name + "\",\"kind\":\"SPECIAL\",\"availableFrom\":\"" + from + "\",\"availableTo\":\"" + to + "\",\"slotRects\":" + FOUR + "}";
    }

    private static MockMultipartHttpServletRequestBuilder upsert(String meta, String svg) {
        MockMultipartHttpServletRequestBuilder builder = multipart(HttpMethod.PUT, "/api/v1/admin/frames").part(jsonPart("meta", meta));
        if (svg != null) {
            builder.file(new MockMultipartFile("frame", "frame.svg", "image/svg+xml", svg.getBytes(StandardCharsets.UTF_8)));
        }
        return (MockMultipartHttpServletRequestBuilder) builder.with(admin());
    }

    private static String read(RequestBody body) throws IOException {
        try (InputStream in = body.contentStreamProvider().newStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
