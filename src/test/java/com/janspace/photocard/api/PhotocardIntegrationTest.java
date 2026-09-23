package com.janspace.photocard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.attendance.application.AttendanceQuery;
import com.janspace.attendance.domain.Attendance;
import com.janspace.attendance.support.AttendanceIntegrationTest;
import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.event.domain.Event;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 포토카드 생성·바인더·삭제, 월 제한 429, 기록 연결(photocardId·BRIGHTEST), AttendanceDeleted·UserWithdrawn 수신,
 * 스티커 세트 배포 기간·관리자 upsert, 인증·권한을 실제 MySQL 로 검증한다. S3 는 IntegrationTest 의 S3Client 목.
 */
class PhotocardIntegrationTest extends AttendanceIntegrationTest {

    static final long U1 = 2L;
    static final long U2 = 3L;
    static final long ADMIN = 99L;
    static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);

    @Autowired
    AttendanceQuery attendanceQuery;

    Event past;

    @BeforeEach
    void fixtures() {
        cleanPhotocardTables();
        insertUser(U1, SEOUL);
        insertUser(U2, SEOUL);
        insertUser(ADMIN, null);
        past = event("지난 공연", TODAY.minusDays(10), venue("KSPO DOME", "서울", "KR", KSPO));
    }

    @AfterEach
    void cleanPhotocardTables() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("photocards", "stickers", "sticker_set_translations", "sticker_sets")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    @DisplayName("생성: S3 photocards/{userId}/{uuid}.{ext} 업로드, imageUrl = CDN, stickers JSON 저장, 바인더는 최신순, 삭제는 소유자만(404), 잘못된 이미지·meta 는 400")
    void create_list_delete() throws Exception {
        DocumentContext first = json(mvc.perform(create(U1, png(120, 80), "photo.png",
                        "{\"stickers\":[{\"stickerId\":7,\"x\":0.4,\"y\":0.6,\"scale\":1.2,\"rotation\":15}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.linkedRecordId").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("Z")))
                .andReturn());
        long firstId = first.read("$.data.id", Long.class);
        assertThat(first.read("$.data.imageUrl", String.class)).matches("https://cdn\\.test/photocards/" + U1 + "/" + UUID_RE + "\\.png");
        assertThat(first.read("$.data.stickers[0].stickerId", Long.class)).isEqualTo(7L);
        assertThat(first.read("$.data.stickers[0].scale", Double.class)).isEqualTo(1.2);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().key()).matches("photocards/" + U1 + "/" + UUID_RE + "\\.png");
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
        assertThat(jdbc.queryForObject("SELECT stickers FROM photocards WHERE id = ?", String.class, firstId))
                .contains("\"stickerId\": 7").contains("\"rotation\": 15.0");

        // JPEG 도 되고, 목록은 최신순. 다른 사용자 바인더에는 없다
        DocumentContext second = json(mvc.perform(create(U1, jpeg(80, 120), "photo.jpg", "{}")).andExpect(status().isOk()).andReturn());
        long secondId = second.read("$.data.id", Long.class);
        assertThat(second.read("$.data.imageUrl", String.class)).endsWith(".jpg");
        assertThat(second.read("$.data.stickers", List.class)).isEmpty();
        mvc.perform(get("/api/v1/photocards").with(user(U1)))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(secondId))
                .andExpect(jsonPath("$.data[1].id").value(firstId));
        mvc.perform(get("/api/v1/photocards").with(user(U2))).andExpect(jsonPath("$.data.length()").value(0));

        // 삭제: 남의 것은 404, 내 것은 소프트 삭제, 두 번째 삭제도 404
        mvc.perform(delete("/api/v1/photocards/{id}", firstId).with(user(U2)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PHOTOCARD_NOT_FOUND"));
        mvc.perform(delete("/api/v1/photocards/{id}", firstId).with(user(U1))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/photocards").with(user(U1))).andExpect(jsonPath("$.data.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM photocards WHERE id = ?", String.class, firstId)).isNotNull();
        mvc.perform(delete("/api/v1/photocards/{id}", firstId).with(user(U1))).andExpect(status().isNotFound());

        // 이미지 아님 / 긴 변 초과 / meta 깨짐
        mvc.perform(create(U1, "not image".getBytes(), "x.png", "{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        mvc.perform(create(U1, png(1351, 10), "wide.png", "{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        mvc.perform(create(U1, png(10, 10), "x.png", "{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photocards", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("월 제한: 이달(UTC) 미삭제 10장이면 429 PHOTOCARD_LIMIT, 삭제된 장·지난달 장은 세지 않는다")
    void monthly_limit() throws Exception {
        for (int i = 0; i < 10; i++) {
            insertPhotocard(U1, "UTC_TIMESTAMP(6)");
        }
        mvc.perform(create(U1, png(10, 10), "x.png", "{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PHOTOCARD_LIMIT"));

        jdbc.update("UPDATE photocards SET deleted_at = UTC_TIMESTAMP(6) WHERE user_id = ? ORDER BY id LIMIT 1", U1);
        mvc.perform(create(U1, png(10, 10), "x.png", "{}")).andExpect(status().isOk());
        mvc.perform(create(U1, png(10, 10), "x.png", "{}")).andExpect(status().isTooManyRequests());

        for (int i = 0; i < 10; i++) {
            insertPhotocard(U2, "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 40 DAY)");
        }
        mvc.perform(create(U2, png(10, 10), "x.png", "{}")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("기록 연결: 내 기록만(남의 것·없는 것 404 RECORD_NOT_FOUND), GET /records 에 photocardId, 별은 BRIGHTEST, 포토카드 삭제 시 photocardId null")
    void link_to_record() throws Exception {
        long rid = record(U1, past, "[\"HAPPY\"]");
        mvc.perform(get("/api/v1/records/{id}", rid).with(user(U1))).andExpect(jsonPath("$.data.photocardId").doesNotExist());
        assertThat(attendanceQuery.star(U1, rid).brightness()).isEqualTo(Attendance.Brightness.BRIGHT);

        mvc.perform(create(U2, png(10, 10), "x.png", "{\"linkedRecordId\":" + rid + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        mvc.perform(create(U1, png(10, 10), "x.png", "{\"linkedRecordId\":999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photocards", Integer.class)).isZero();

        DocumentContext card = json(mvc.perform(create(U1, png(10, 10), "x.png", "{\"linkedRecordId\":" + rid + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.linkedRecordId").value(rid)).andReturn());
        long cardId = card.read("$.data.id", Long.class);
        mvc.perform(get("/api/v1/records").with(user(U1)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].photocardId").value(cardId));
        mvc.perform(get("/api/v1/records/{id}", rid).with(user(U1))).andExpect(jsonPath("$.data.photocardId").value(cardId));
        assertThat(attendanceQuery.star(U1, rid).brightness()).isEqualTo(Attendance.Brightness.BRIGHTEST);

        // 한 기록에 두 장이면 최신 장. 포토카드를 지우면 기록의 photocardId 도 사라진다
        DocumentContext later = json(mvc.perform(create(U1, png(10, 10), "x.png", "{\"linkedRecordId\":" + rid + "}")).andReturn());
        long laterId = later.read("$.data.id", Long.class);
        mvc.perform(get("/api/v1/records/{id}", rid).with(user(U1))).andExpect(jsonPath("$.data.photocardId").value(laterId));
        mvc.perform(delete("/api/v1/photocards/{id}", laterId).with(user(U1))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/records/{id}", rid).with(user(U1))).andExpect(jsonPath("$.data.photocardId").value(cardId));
        mvc.perform(delete("/api/v1/photocards/{id}", cardId).with(user(U1))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/records/{id}", rid).with(user(U1))).andExpect(jsonPath("$.data.photocardId").doesNotExist());
        assertThat(attendanceQuery.star(U1, rid).brightness()).isEqualTo(Attendance.Brightness.BRIGHT);
    }

    @Test
    @DisplayName("AttendanceDeleted: 기록을 지우면 연결만 끊기고 포토카드는 바인더에 남는다")
    void attendance_deleted_unlinks() throws Exception {
        long rid = record(U1, past, "[]");
        DocumentContext card = json(mvc.perform(create(U1, png(10, 10), "x.png", "{\"linkedRecordId\":" + rid + "}")).andReturn());
        long cardId = card.read("$.data.id", Long.class);

        mvc.perform(delete("/api/v1/records/{id}", rid).with(user(U1))).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT attendance_id FROM photocards WHERE id = ?", Long.class, cardId)).isNull();
        mvc.perform(get("/api/v1/photocards").with(user(U1)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(cardId))
                .andExpect(jsonPath("$.data[0].linkedRecordId").doesNotExist());
    }

    @Test
    @DisplayName("UserWithdrawn: 그 사용자의 포토카드만 소프트 삭제된다")
    void user_withdrawn_soft_deletes() throws Exception {
        long mine = json(mvc.perform(create(U1, png(10, 10), "x.png", "{}")).andReturn()).read("$.data.id", Long.class);
        long other = json(mvc.perform(create(U2, png(10, 10), "x.png", "{}")).andReturn()).read("$.data.id", Long.class);

        publisher.publishEvent(new UserWithdrawn(U1));

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM photocards WHERE id = ?", String.class, mine)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM photocards WHERE id = ?", String.class, other)).isNull();
        mvc.perform(get("/api/v1/photocards").with(user(U1))).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/v1/photocards").with(user(U2))).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("관리자 upsert: 번역 + 파일 2개 -> StickerSet 모양, id 로 다시 올리면 파일이 뒤에 붙고 번역은 전체 교체, 스티커 삭제 404 규칙, 잘못된 파일은 아무것도 만들지 않는다")
    void admin_upsert_and_delete_sticker() throws Exception {
        String meta = "{\"name\":{\"original\":\"합창의 밤\",\"en\":\"Choir Night\",\"ja\":\"合唱の夜\"},\"season\":\"합창의 밤 2026\","
                + "\"availableFrom\":\"" + TODAY_UTC.minusDays(1) + "\",\"availableTo\":\"" + TODAY_UTC.plusDays(1) + "\"}";
        DocumentContext created = json(mvc.perform(upsert(meta, png(64, 64), png(32, 32)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK")).andReturn());
        long setId = created.read("$.data.id", Long.class);
        assertThat(created.read("$.data.name.original", String.class)).isEqualTo("합창의 밤");
        assertThat(created.read("$.data.name.ko", String.class)).isEqualTo("합창의 밤");
        assertThat(created.read("$.data.name.en", String.class)).isEqualTo("Choir Night");
        assertThat(created.read("$.data.name.ja", String.class)).isEqualTo("合唱の夜");
        assertThat(created.read("$.data.season", String.class)).isEqualTo("합창의 밤 2026");
        assertThat(created.read("$.data.availableFrom", String.class)).isEqualTo(TODAY_UTC.minusDays(1).toString());
        assertThat(created.read("$.data.availableTo", String.class)).isEqualTo(TODAY_UTC.plusDays(1).toString());
        assertThat(created.read("$.data.isDefault", Boolean.class)).isFalse();
        List<Integer> stickerIds = created.read("$.data.stickers[*].id", List.class);
        assertThat(stickerIds).hasSize(2).isSorted();
        assertThat(created.read("$.data.stickers[0].setId", Long.class)).isEqualTo(setId);
        assertThat(created.read("$.data.stickers[0].imageUrl", String.class)).matches("https://cdn\\.test/stickers/" + setId + "/" + UUID_RE + "\\.png");
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getAllValues()).allSatisfy(r -> assertThat(r.key()).startsWith("stickers/" + setId + "/"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sticker_set_translations WHERE sticker_set_id = ?", Integer.class, setId)).isEqualTo(2);

        // id 로 다시: 파일은 뒤에 추가, 번역은 전체 교체(ja 제거), isDefault 반영
        DocumentContext updated = json(mvc.perform(upsert("{\"id\":" + setId + ",\"name\":{\"original\":\"합창의 밤\",\"en\":\"Choir Night 2\"},\"isDefault\":true}",
                        jpeg(16, 16)))
                .andExpect(status().isOk()).andReturn());
        assertThat(updated.read("$.data.id", Long.class)).isEqualTo(setId);
        assertThat(updated.read("$.data.name.en", String.class)).isEqualTo("Choir Night 2");
        assertThat(updated.read("$.data.name", java.util.Map.class)).doesNotContainKey("ja");
        assertThat(updated.read("$.data.isDefault", Boolean.class)).isTrue();
        List<Integer> afterIds = updated.read("$.data.stickers[*].id", List.class);
        assertThat(afterIds).hasSize(3).startsWith(stickerIds.toArray(new Integer[0]));
        assertThat(updated.read("$.data.stickers[2].imageUrl", String.class)).endsWith(".jpg");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sticker_set_translations WHERE sticker_set_id = ?", Integer.class, setId)).isEqualTo(1);

        // 스티커 삭제
        mvc.perform(delete("/api/v1/admin/sticker-sets/{id}/stickers/{stickerId}", setId, stickerIds.get(0)).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/sticker-sets")).andExpect(jsonPath("$.data[0].stickers.length()").value(2))
                .andExpect(jsonPath("$.data[0].stickers[0].id").value(stickerIds.get(1)));
        mvc.perform(delete("/api/v1/admin/sticker-sets/{id}/stickers/{stickerId}", setId, stickerIds.get(0)).with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STICKER_NOT_FOUND"));
        mvc.perform(delete("/api/v1/admin/sticker-sets/{id}/stickers/{stickerId}", 999999, stickerIds.get(1)).with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STICKER_SET_NOT_FOUND"));
        mvc.perform(upsert("{\"id\":999999,\"name\":{\"original\":\"x\"}}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("STICKER_SET_NOT_FOUND"));

        // 파일 하나라도 틀리면 세트를 만들지 않는다
        mvc.perform(upsert("{\"name\":{\"original\":\"깨진 세트\"}}", png(8, 8), "not image".getBytes()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sticker_sets", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("GET /sticker-sets (공개): 오늘이 배포 기간 안인 세트만. 지난 세트·미래 세트는 숨기고 날짜 없는 세트는 상시")
    void sticker_sets_availability_window() throws Exception {
        long open = upsertSet("상시", null, null);
        long current = upsertSet("현재", TODAY_UTC.minusDays(3), TODAY_UTC.plusDays(3));
        long startsToday = upsertSet("오늘부터", TODAY_UTC, null);
        long endsToday = upsertSet("오늘까지", null, TODAY_UTC);
        upsertSet("지난", TODAY_UTC.minusDays(30), TODAY_UTC.minusDays(1));
        upsertSet("미래", TODAY_UTC.plusDays(1), TODAY_UTC.plusDays(30));

        DocumentContext listed = json(mvc.perform(get("/api/v1/sticker-sets")).andExpect(status().isOk()).andReturn());
        assertThat(listed.read("$.data[*].id", List.class))
                .containsExactly((int) open, (int) current, (int) startsToday, (int) endsToday);
        assertThat(listed.read("$.data[*].name.original", List.class)).containsExactly("상시", "현재", "오늘부터", "오늘까지");
    }

    @Test
    @DisplayName("인증·권한: 비로그인 POST/GET /photocards 는 401, 비관리자의 /admin/sticker-sets 는 403")
    void auth_rules() throws Exception {
        mvc.perform(multipart(HttpMethod.POST, "/api/v1/photocards")
                        .file(new MockMultipartFile("image", "x.png", "image/png", png(10, 10)))
                        .part(new MockPart("meta", "{}".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/photocards")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/admin/sticker-sets")
                        .part(new MockPart("meta", "{\"name\":{\"original\":\"x\"}}".getBytes(StandardCharsets.UTF_8)))
                        .with(user(U1)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(delete("/api/v1/admin/sticker-sets/1/stickers/1").with(user(U1)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sticker_sets", Integer.class)).isZero();
    }

    private long record(long userId, Event event, String emotionsJson) throws Exception {
        mvc.perform(post("/api/v1/records").with(user(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(event.getId(), emotionsJson, "[]".equals(emotionsJson), null, null)))
                .andExpect(status().isOk());
        return row(userId, event.getId()).getId();
    }

    private void insertPhotocard(long userId, String createdAtSql) {
        jdbc.update("INSERT INTO photocards (user_id, image_key, stickers, created_at, updated_at) VALUES (?, 'photocards/x.png', '[]', "
                + createdAtSql + ", UTC_TIMESTAMP(6))", userId);
    }

    private long upsertSet(String name, LocalDate from, LocalDate to) throws Exception {
        String meta = "{\"name\":{\"original\":\"" + name + "\"}"
                + (from == null ? "" : ",\"availableFrom\":\"" + from + "\"")
                + (to == null ? "" : ",\"availableTo\":\"" + to + "\"") + "}";
        return json(mvc.perform(upsert(meta)).andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
    }

    private static MockMultipartHttpServletRequestBuilder create(long userId, byte[] image, String filename, String meta) {
        String contentType = filename.endsWith(".jpg") ? "image/jpeg" : "image/png";
        return (MockMultipartHttpServletRequestBuilder) multipart(HttpMethod.POST, "/api/v1/photocards")
                .file(new MockMultipartFile("image", filename, contentType, image))
                .part(new MockPart("meta", meta.getBytes(StandardCharsets.UTF_8)))
                .with(user(userId));
    }

    private static MockMultipartHttpServletRequestBuilder upsert(String meta, byte[]... files) {
        MockMultipartHttpServletRequestBuilder builder = multipart(HttpMethod.PUT, "/api/v1/admin/sticker-sets")
                .part(new MockPart("meta", meta.getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < files.length; i++) {
            builder.file(new MockMultipartFile("stickers", "sticker" + i + ".png", "image/png", files[i]));
        }
        return (MockMultipartHttpServletRequestBuilder) builder.with(admin());
    }

    // 관리자: ADMIN 권한 + 동의한 users 행(쓰기 요청은 동의 게이트를 지난다)
    private static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ADMIN, true), null,
                List.of(new SimpleGrantedAuthority("ADMIN"))));
    }

    static byte[] png(int width, int height) throws IOException {
        return image(width, height, "png");
    }

    static byte[] jpeg(int width, int height) throws IOException {
        return image(width, height, "jpg");
    }

    static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage img = new BufferedImage(width, height, "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static DocumentContext json(MvcResult result) throws Exception {
        return JsonPath.parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
