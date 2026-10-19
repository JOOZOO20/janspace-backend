package com.stagelog.photocard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.account.application.event.UserWithdrawn;
import com.stagelog.attendance.application.AttendanceQuery;
import com.stagelog.attendance.domain.Attendance;
import com.stagelog.attendance.support.AttendanceIntegrationTest;
import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.event.domain.Event;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * 포토카드·네컷 생성(종류별 비율·크기·프레임·메타 검증), 바인더, 연결 수정(PATCH), 월 제한·남은 수, 삭제·탈퇴 시 S3 객체 정리,
 * 기록 1:1 연결(photocardId·BRIGHTEST), AttendanceDeleted 수신, 스티커 세트 배포 기간·관리자 목록·upsert, 인증·권한을 실제 MySQL 로 검증한다.
 * S3 는 IntegrationTest 의 S3Client 목.
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
    Event earlier;

    @BeforeEach
    void fixtures() {
        cleanPhotocardTables();
        insertUser(U1, SEOUL);
        insertUser(U2, SEOUL);
        insertUser(ADMIN, null);
        past = event("지난 공연", TODAY.minusDays(10), venue("KSPO DOME", "서울", "KR", KSPO));
        earlier = event("더 지난 공연", TODAY.minusDays(40), venue("KSPO DOME", "서울", "KR", KSPO));
    }

    @AfterEach
    void cleanPhotocardTables() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("photocards", "frames", "stickers", "sticker_set_translations", "sticker_sets")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    @DisplayName("생성: S3 photocards/{userId}/{uuid}.{ext}, kind·배경색·스티커(숫자·code:*) 저장, 바인더는 최신순, 삭제는 소유자만(404) + 커밋 후 S3 객체 삭제")
    void create_list_delete() throws Exception {
        DocumentContext first = json(mvc.perform(create(U1, card(), "photo.png",
                        "{\"backgroundColor\":\"#1B2A4A\",\"stickers\":[{\"stickerId\":\"7\",\"x\":540,\"y\":675,\"scale\":1.2,\"rotation\":15},"
                                + "{\"stickerId\":\"code:date:retro:ymd:2026-10-23\",\"x\":1,\"y\":2,\"scale\":1,\"rotation\":0}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.kind").value("CARD"))
                .andExpect(jsonPath("$.data.backgroundColor").value("#1B2A4A"))
                .andExpect(jsonPath("$.data.frameId").doesNotExist())
                .andExpect(jsonPath("$.data.linkedRecordId").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("Z")))
                .andReturn());
        long firstId = first.read("$.data.id", Long.class);
        String firstUrl = first.read("$.data.imageUrl", String.class);
        assertThat(firstUrl).matches("https://cdn\\.test/photocards/" + U1 + "/" + UUID_RE + "\\.png");
        assertThat(first.read("$.data.stickers[0].stickerId", String.class)).isEqualTo("7");
        assertThat(first.read("$.data.stickers[1].stickerId", String.class)).isEqualTo("code:date:retro:ymd:2026-10-23");
        assertThat(first.read("$.data.stickers[0].scale", Double.class)).isEqualTo(1.2);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        String firstKey = captor.getValue().key();
        assertThat(firstKey).matches("photocards/" + U1 + "/" + UUID_RE + "\\.png");
        assertThat(captor.getValue().contentType()).isEqualTo("image/png");
        assertThat(jdbc.queryForMap("SELECT kind, background_color, stickers FROM photocards WHERE id = ?", firstId))
                .containsEntry("kind", "CARD").containsEntry("background_color", "#1B2A4A");
        assertThat(jdbc.queryForObject("SELECT stickers FROM photocards WHERE id = ?", String.class, firstId))
                .contains("\"stickerId\": \"7\"").contains("\"rotation\": 15.0");

        // JPEG 도 되고, kind 생략은 CARD. 목록은 최신순. 다른 사용자 바인더에는 없다
        DocumentContext second = json(mvc.perform(create(U1, jpeg(80, 100), "photo.jpg", "{}")).andExpect(status().isOk()).andReturn());
        long secondId = second.read("$.data.id", Long.class);
        assertThat(second.read("$.data.imageUrl", String.class)).endsWith(".jpg");
        assertThat(second.read("$.data.kind", String.class)).isEqualTo("CARD");
        assertThat(second.read("$.data.stickers", List.class)).isEmpty();
        mvc.perform(get("/api/v1/photocards").with(user(U1)))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(secondId))
                .andExpect(jsonPath("$.data[1].id").value(firstId));
        mvc.perform(get("/api/v1/photocards").with(user(U2))).andExpect(jsonPath("$.data.length()").value(0));

        // 삭제: 남의 것은 404(객체 그대로), 내 것은 소프트 삭제 + 객체 삭제, 두 번째 삭제도 404
        mvc.perform(delete("/api/v1/photocards/{id}", firstId).with(user(U2)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PHOTOCARD_NOT_FOUND"));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        mvc.perform(delete("/api/v1/photocards/{id}", firstId).with(user(U1))).andExpect(status().isOk());
        assertThat(deletedKeys()).containsExactly(firstKey);
        mvc.perform(get("/api/v1/photocards").with(user(U1))).andExpect(jsonPath("$.data.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM photocards WHERE id = ?", String.class, firstId)).isNotNull();
        mvc.perform(delete("/api/v1/photocards/{id}", firstId).with(user(U1))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("입력 검증: 이미지 아님·카드 긴 변 1350 초과·4:5 아님은 PHOTOCARD_IMAGE_INVALID, 깨진 meta·잘못된 kind·배경색·stickerId 는 400, JSON 아닌 meta 파트는 415")
    void create_validation() throws Exception {
        mvc.perform(create(U1, "not image".getBytes(), "x.png", "{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        mvc.perform(create(U1, png(1081, 1351), "big.png", "{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        mvc.perform(create(U1, png(100, 100), "square.png", "{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        // 프론트 목이 보내던 소문자 kind 는 받지 않는다
        for (String meta : List.of("{", "{\"kind\":\"card\"}", "{\"backgroundColor\":\"red\"}", "{\"backgroundColor\":\"#12345\"}",
                "{\"stickers\":[{\"stickerId\":\"../etc\",\"x\":0,\"y\":0,\"scale\":1,\"rotation\":0}]}",
                "{\"stickers\":[{\"x\":0,\"y\":0,\"scale\":1,\"rotation\":0}]}")) {
            mvc.perform(create(U1, card(), "x.png", meta)).andExpect(status().isBadRequest());
        }
        mvc.perform(multipart(HttpMethod.POST, "/api/v1/photocards")
                        .file(new MockMultipartFile("image", "x.png", "image/png", card()))
                        .part(new MockPart("meta", "{}".getBytes(StandardCharsets.UTF_8)))
                        .with(user(U1)))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photocards", Integer.class)).isZero();
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("네컷: 1:3 이미지(긴 변 3240 까지)와 프레임 ID 를 저장한다. 비율·크기 오류는 400, CARD 의 frameId 는 400, 없는 프레임은 404 FRAME_NOT_FOUND")
    void fourcut() throws Exception {
        long frameId = insertFrame("클린");
        DocumentContext made = json(mvc.perform(create(U1, png(1080, 3240), "fourcut.png",
                        "{\"kind\":\"FOURCUT\",\"frameId\":" + frameId + ",\"backgroundColor\":\"#ffffff\"}"))
                .andExpect(status().isOk()).andReturn());
        assertThat(made.read("$.data.kind", String.class)).isEqualTo("FOURCUT");
        assertThat(made.read("$.data.frameId", Long.class)).isEqualTo(frameId);
        // 프레임 없이 만든 네컷도 된다
        mvc.perform(create(U1, png(10, 30), "f.png", "{\"kind\":\"FOURCUT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.frameId").doesNotExist());

        mvc.perform(create(U1, png(1081, 3243), "f.png", "{\"kind\":\"FOURCUT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        mvc.perform(create(U1, card(), "f.png", "{\"kind\":\"FOURCUT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        mvc.perform(create(U1, png(10, 30), "f.png", "{\"kind\":\"CARD\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        mvc.perform(create(U1, card(), "c.png", "{\"frameId\":" + frameId + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(create(U1, png(10, 30), "f.png", "{\"kind\":\"FOURCUT\",\"frameId\":999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FRAME_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photocards", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("월 제한: 카드·네컷 합산 이달(UTC) 미삭제 10장이면 429 PHOTOCARD_LIMIT, 삭제된 장·지난달 장은 세지 않고, /photocards/quota 가 남은 수를 낸다")
    void monthly_limit_and_quota() throws Exception {
        String nextMonth = YearMonth.now(ZoneOffset.UTC).plusMonths(1).atDay(1) + "T00:00:00Z";
        mvc.perform(get("/api/v1/photocards/quota").with(user(U1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.limit").value(10))
                .andExpect(jsonPath("$.data.remaining").value(10))
                .andExpect(jsonPath("$.data.resetsAt").value(nextMonth));
        for (int i = 0; i < 9; i++) {
            insertPhotocard(U1, "UTC_TIMESTAMP(6)");
        }
        mvc.perform(create(U1, png(10, 30), "f.png", "{\"kind\":\"FOURCUT\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/photocards/quota").with(user(U1))).andExpect(jsonPath("$.data.remaining").value(0));
        mvc.perform(create(U1, card(), "x.png", "{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PHOTOCARD_LIMIT"));

        jdbc.update("UPDATE photocards SET deleted_at = UTC_TIMESTAMP(6) WHERE user_id = ? ORDER BY id LIMIT 1", U1);
        mvc.perform(get("/api/v1/photocards/quota").with(user(U1))).andExpect(jsonPath("$.data.remaining").value(1));
        mvc.perform(create(U1, card(), "x.png", "{}")).andExpect(status().isOk());
        mvc.perform(create(U1, card(), "x.png", "{}")).andExpect(status().isTooManyRequests());

        for (int i = 0; i < 10; i++) {
            insertPhotocard(U2, "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 40 DAY)");
        }
        mvc.perform(get("/api/v1/photocards/quota").with(user(U2))).andExpect(jsonPath("$.data.remaining").value(10));
        mvc.perform(create(U2, card(), "x.png", "{}")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("기록 연결 1:1: 내 기록만(404 RECORD_NOT_FOUND), 이미 다른 카드가 붙은 기록은 409 PHOTOCARD_ALREADY_LINKED, 카드를 지우면 기록이 풀려 다시 붙일 수 있다")
    void link_to_record() throws Exception {
        long rid = record(U1, past, "[\"HAPPY\"]");
        mvc.perform(get("/api/v1/records/{id}", rid).with(user(U1))).andExpect(jsonPath("$.data.photocardId").doesNotExist());
        assertThat(attendanceQuery.star(U1, rid).brightness()).isEqualTo(Attendance.Brightness.BRIGHT);

        mvc.perform(create(U2, card(), "x.png", "{\"linkedRecordId\":" + rid + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        mvc.perform(create(U1, card(), "x.png", "{\"linkedRecordId\":999999}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photocards", Integer.class)).isZero();

        long cardId = json(mvc.perform(create(U1, card(), "x.png", "{\"linkedRecordId\":" + rid + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.linkedRecordId").value(rid)).andReturn()).read("$.data.id", Long.class);
        mvc.perform(get("/api/v1/records").with(user(U1)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].photocardId").value(cardId));
        mvc.perform(get("/api/v1/records/{id}", rid).with(user(U1))).andExpect(jsonPath("$.data.photocardId").value(cardId));
        assertThat(attendanceQuery.star(U1, rid).brightness()).isEqualTo(Attendance.Brightness.BRIGHTEST);

        // 두 번째 카드는 같은 기록에 붙지 않는다 (업로드도 하지 않는다)
        mvc.perform(create(U1, card(), "x.png", "{\"linkedRecordId\":" + rid + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHOTOCARD_ALREADY_LINKED"));
        verify(s3Client, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));

        // 카드를 지우면 기록의 photocardId 가 사라지고 그 기록에 새 카드를 붙일 수 있다
        mvc.perform(delete("/api/v1/photocards/{id}", cardId).with(user(U1))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/records/{id}", rid).with(user(U1))).andExpect(jsonPath("$.data.photocardId").doesNotExist());
        assertThat(attendanceQuery.star(U1, rid).brightness()).isEqualTo(Attendance.Brightness.BRIGHT);
        long again = json(mvc.perform(create(U1, card(), "x.png", "{\"linkedRecordId\":" + rid + "}"))
                .andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
        mvc.perform(get("/api/v1/records/{id}", rid).with(user(U1))).andExpect(jsonPath("$.data.photocardId").value(again));
    }

    @Test
    @DisplayName("PATCH 연결 수정: 키 없음 = 유지, null = 끊기, 다른 기록으로 옮기기(옛 기록 photocardId 해제), 붙은 기록 409, 남의 카드·남의 기록 404")
    void patch_relink() throws Exception {
        long r1 = record(U1, past, "[\"HAPPY\"]");
        long r2 = record(U1, earlier, "[\"SAD\"]");
        long otherRecord = record(U2, past, "[\"HAPPY\"]");
        long a = json(mvc.perform(create(U1, card(), "a.png", "{\"linkedRecordId\":" + r1 + "}")).andReturn()).read("$.data.id", Long.class);
        long b = json(mvc.perform(create(U1, card(), "b.png", "{}")).andReturn()).read("$.data.id", Long.class);

        // 키 없음: 그대로
        mvc.perform(relink(U1, a, "{}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.linkedRecordId").value(r1));
        // 이미 a 가 붙은 r1 에 b 를 붙이려 하면 409
        mvc.perform(relink(U1, b, "{\"linkedRecordId\":" + r1 + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHOTOCARD_ALREADY_LINKED"));
        // 같은 기록으로 다시 보내면 그대로 성공
        mvc.perform(relink(U1, a, "{\"linkedRecordId\":" + r1 + "}")).andExpect(status().isOk());
        // a 를 r2 로 옮기면 r1 은 풀리고, b 를 r1 에 붙일 수 있다
        mvc.perform(relink(U1, a, "{\"linkedRecordId\":" + r2 + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.linkedRecordId").value(r2)).andExpect(jsonPath("$.data.kind").value("CARD"));
        mvc.perform(get("/api/v1/records/{id}", r1).with(user(U1))).andExpect(jsonPath("$.data.photocardId").doesNotExist());
        mvc.perform(get("/api/v1/records/{id}", r2).with(user(U1))).andExpect(jsonPath("$.data.photocardId").value(a));
        mvc.perform(relink(U1, b, "{\"linkedRecordId\":" + r1 + "}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/records/{id}", r1).with(user(U1))).andExpect(jsonPath("$.data.photocardId").value(b));
        // null: 끊기
        mvc.perform(relink(U1, a, "{\"linkedRecordId\":null}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.linkedRecordId").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT attendance_id FROM photocards WHERE id = ?", Long.class, a)).isNull();
        mvc.perform(get("/api/v1/records/{id}", r2).with(user(U1))).andExpect(jsonPath("$.data.photocardId").doesNotExist());

        // 남의 카드·남의 기록·없는 카드·지운 카드
        mvc.perform(relink(U2, a, "{\"linkedRecordId\":" + otherRecord + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PHOTOCARD_NOT_FOUND"));
        mvc.perform(relink(U1, a, "{\"linkedRecordId\":" + otherRecord + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
        mvc.perform(relink(U1, 999999, "{}")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PHOTOCARD_NOT_FOUND"));
        mvc.perform(delete("/api/v1/photocards/{id}", b).with(user(U1))).andExpect(status().isOk());
        mvc.perform(relink(U1, b, "{\"linkedRecordId\":null}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AttendanceDeleted: 기록을 지우면 연결만 끊기고 포토카드는 바인더에 남는다")
    void attendance_deleted_unlinks() throws Exception {
        long rid = record(U1, past, "[]");
        long cardId = json(mvc.perform(create(U1, card(), "x.png", "{\"linkedRecordId\":" + rid + "}")).andReturn()).read("$.data.id", Long.class);

        mvc.perform(delete("/api/v1/records/{id}", rid).with(user(U1))).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT attendance_id FROM photocards WHERE id = ?", Long.class, cardId)).isNull();
        mvc.perform(get("/api/v1/photocards").with(user(U1)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(cardId))
                .andExpect(jsonPath("$.data[0].linkedRecordId").doesNotExist());
    }

    @Test
    @DisplayName("UserWithdrawn: 그 사용자의 포토카드만 소프트 삭제되고, 커밋 후 그 사용자의 이미지 객체(이미 지운 카드 포함)만 S3 에서 지운다")
    void user_withdrawn_soft_deletes_and_removes_objects() throws Exception {
        long mine = json(mvc.perform(create(U1, card(), "x.png", "{}")).andReturn()).read("$.data.id", Long.class);
        long mine2 = json(mvc.perform(create(U1, card(), "x.png", "{}")).andReturn()).read("$.data.id", Long.class);
        long other = json(mvc.perform(create(U2, card(), "x.png", "{}")).andReturn()).read("$.data.id", Long.class);
        jdbc.update("UPDATE photocards SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", mine2);
        List<String> myKeys = jdbc.queryForList("SELECT image_key FROM photocards WHERE user_id = ? ORDER BY id", String.class, U1);

        publisher.publishEvent(new UserWithdrawn(U1));

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM photocards WHERE id = ?", String.class, mine)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM photocards WHERE id = ?", String.class, other)).isNull();
        assertThat(deletedKeys()).containsExactlyInAnyOrderElementsOf(myKeys).allMatch(k -> k.startsWith("photocards/" + U1 + "/"));
        mvc.perform(get("/api/v1/photocards").with(user(U1))).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/v1/photocards").with(user(U2))).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("UserWithdrawn: S3 삭제가 실패해도 포토카드 정리는 커밋된다(WARN 만)")
    void user_withdrawn_survives_storage_failure() throws Exception {
        long mine = json(mvc.perform(create(U1, card(), "x.png", "{}")).andReturn()).read("$.data.id", Long.class);
        doThrow(SdkClientException.create("S3 다운")).when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        publisher.publishEvent(new UserWithdrawn(U1));

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM photocards WHERE id = ?", String.class, mine)).isNotNull();
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    @DisplayName("관리자 upsert: 번역 + 파일 2개(이름·분류 라벨) -> StickerSet 모양, id 로 다시 올리면 파일이 뒤에 붙고 번역은 전체 교체, 스티커 삭제 404 규칙, 잘못된 파일·라벨 수는 아무것도 만들지 않는다")
    void admin_upsert_and_delete_sticker() throws Exception {
        String meta = "{\"name\":{\"original\":\"빛의 밤\",\"en\":\"Night of Lights\",\"ja\":\"光の夜\"},\"season\":\"빛의 밤 2026\","
                + "\"availableFrom\":\"" + TODAY_UTC.minusDays(1) + "\",\"availableTo\":\"" + TODAY_UTC.plusDays(1) + "\","
                + "\"newStickers\":[{\"name\":\"하트\",\"category\":\"heart\",\"group\":\"pattern\"},{}]}";
        DocumentContext created = json(mvc.perform(upsert(meta, png(64, 64), png(32, 32)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK")).andReturn());
        long setId = created.read("$.data.id", Long.class);
        assertThat(created.read("$.data.name.original", String.class)).isEqualTo("빛의 밤");
        assertThat(created.read("$.data.name.ko", String.class)).isEqualTo("빛의 밤");
        assertThat(created.read("$.data.name.en", String.class)).isEqualTo("Night of Lights");
        assertThat(created.read("$.data.name.ja", String.class)).isEqualTo("光の夜");
        assertThat(created.read("$.data.season", String.class)).isEqualTo("빛의 밤 2026");
        assertThat(created.read("$.data.availableFrom", String.class)).isEqualTo(TODAY_UTC.minusDays(1).toString());
        assertThat(created.read("$.data.availableTo", String.class)).isEqualTo(TODAY_UTC.plusDays(1).toString());
        assertThat(created.read("$.data.isDefault", Boolean.class)).isFalse();
        List<Integer> stickerIds = created.read("$.data.stickers[*].id", List.class);
        assertThat(stickerIds).hasSize(2).isSorted();
        assertThat(created.read("$.data.stickers[0].setId", Long.class)).isEqualTo(setId);
        assertThat(created.read("$.data.stickers[0].imageUrl", String.class)).matches("https://cdn\\.test/stickers/" + setId + "/" + UUID_RE + "\\.png");
        assertThat(created.read("$.data.stickers[0].name", Map.class)).isEqualTo(Map.of("original", "하트", "ko", "하트"));
        assertThat(created.read("$.data.stickers[0].category", String.class)).isEqualTo("heart");
        assertThat(created.read("$.data.stickers[0].group", String.class)).isEqualTo("pattern");
        assertThat(created.read("$.data.stickers[1].name", Object.class)).isNull();
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getAllValues()).allSatisfy(r -> assertThat(r.key()).startsWith("stickers/" + setId + "/"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sticker_set_translations WHERE sticker_set_id = ?", Integer.class, setId)).isEqualTo(2);

        // id 로 다시: 파일은 뒤에 추가(라벨 생략 가능), 번역은 전체 교체(ja 제거), isDefault 반영
        DocumentContext updated = json(mvc.perform(upsert("{\"id\":" + setId + ",\"name\":{\"original\":\"빛의 밤\",\"en\":\"Night of Lights 2\"},\"isDefault\":true}",
                        jpeg(16, 16)))
                .andExpect(status().isOk()).andReturn());
        assertThat(updated.read("$.data.id", Long.class)).isEqualTo(setId);
        assertThat(updated.read("$.data.name.en", String.class)).isEqualTo("Night of Lights 2");
        assertThat(updated.read("$.data.name", Map.class)).doesNotContainKey("ja");
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

        // 파일 하나라도 틀리거나, 라벨 수가 파일 수와 다르거나, 이름이 너무 길면 세트를 만들지 않는다
        mvc.perform(upsert("{\"name\":{\"original\":\"깨진 세트\"}}", png(8, 8), "not image".getBytes()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"));
        mvc.perform(upsert("{\"name\":{\"original\":\"라벨 수\"},\"newStickers\":[{}]}", png(8, 8), png(8, 8)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(upsert("{\"name\":{\"original\":\"" + "가".repeat(101) + "\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(upsert("{\"name\":{\"original\":\"x\"},\"newStickers\":[{\"category\":\"" + "c".repeat(31) + "\"}]}", png(8, 8)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sticker_sets", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("스티커 세트 목록: 공개는 오늘이 배포 기간 안인 세트만(날짜 없는 세트는 상시), 관리자 목록은 지난 세트·미래 세트까지 전부")
    void sticker_sets_availability_window() throws Exception {
        long open = upsertSet("상시", null, null);
        long current = upsertSet("현재", TODAY_UTC.minusDays(3), TODAY_UTC.plusDays(3));
        long startsToday = upsertSet("오늘부터", TODAY_UTC, null);
        long endsToday = upsertSet("오늘까지", null, TODAY_UTC);
        long ended = upsertSet("지난", TODAY_UTC.minusDays(30), TODAY_UTC.minusDays(1));
        long future = upsertSet("미래", TODAY_UTC.plusDays(1), TODAY_UTC.plusDays(30));

        DocumentContext listed = json(mvc.perform(get("/api/v1/sticker-sets")).andExpect(status().isOk()).andReturn());
        assertThat(listed.read("$.data[*].id", List.class))
                .containsExactly((int) open, (int) current, (int) startsToday, (int) endsToday);
        assertThat(listed.read("$.data[*].name.original", List.class)).containsExactly("상시", "현재", "오늘부터", "오늘까지");

        DocumentContext all = json(mvc.perform(get("/api/v1/admin/sticker-sets").with(admin())).andExpect(status().isOk()).andReturn());
        assertThat(all.read("$.data[*].id", List.class))
                .containsExactly((int) open, (int) current, (int) startsToday, (int) endsToday, (int) ended, (int) future);
    }

    @Test
    @DisplayName("인증·권한: 비로그인 POST/GET/PATCH /photocards·quota 는 401, 비관리자의 /admin/sticker-sets 는 403")
    void auth_rules() throws Exception {
        mvc.perform(multipart(HttpMethod.POST, "/api/v1/photocards")
                        .file(new MockMultipartFile("image", "x.png", "image/png", card()))
                        .part(jsonPart("meta", "{}")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/photocards")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/photocards/quota")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/photocards/1").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());

        mvc.perform(multipart(HttpMethod.PUT, "/api/v1/admin/sticker-sets")
                        .part(jsonPart("meta", "{\"name\":{\"original\":\"x\"}}"))
                        .with(user(U1)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/sticker-sets").with(user(U1))).andExpect(status().isForbidden());
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
        jdbc.update("INSERT INTO photocards (user_id, image_key, kind, stickers, created_at, updated_at) VALUES (?, 'photocards/x.png', 'CARD', '[]', "
                + createdAtSql + ", UTC_TIMESTAMP(6))", userId);
    }

    private long insertFrame(String name) {
        jdbc.update("INSERT INTO frames (name, kind, image_key, slot_rects, created_at, updated_at) "
                + "VALUES (?, 'ORIGINAL', 'frames/x.svg', '[]', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", name);
        return jdbc.queryForObject("SELECT MAX(id) FROM frames", Long.class);
    }

    private List<String> deletedKeys() {
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, org.mockito.Mockito.atLeastOnce()).deleteObject(captor.capture());
        return captor.getAllValues().stream().map(DeleteObjectRequest::key).toList();
    }

    private long upsertSet(String name, LocalDate from, LocalDate to) throws Exception {
        String meta = "{\"name\":{\"original\":\"" + name + "\"}"
                + (from == null ? "" : ",\"availableFrom\":\"" + from + "\"")
                + (to == null ? "" : ",\"availableTo\":\"" + to + "\"") + "}";
        return json(mvc.perform(upsert(meta)).andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
    }

    @Test
    @DisplayName("이미지 오류 메시지는 종류별 상한을 말한다: 카드 5MB·1350px, 네컷 9MB·3240px")
    void image_invalid_message_per_kind() throws Exception {
        byte[] notImage = "not an image".getBytes(StandardCharsets.UTF_8);
        mvc.perform(create(U1, notImage, "c.png", "{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"))
                .andExpect(jsonPath("$.message").value("이미지는 5MB 이하, 긴 변 1350px 이하의 PNG 또는 JPEG 여야 합니다."));
        mvc.perform(create(U1, notImage, "f.png", "{\"kind\":\"FOURCUT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTOCARD_IMAGE_INVALID"))
                .andExpect(jsonPath("$.message").value("이미지는 9MB 이하, 긴 변 3240px 이하의 PNG 또는 JPEG 여야 합니다."));
    }

    @Test
    @DisplayName("스티커 업로드 중간 실패: 롤백되면 이미 올린 스티커 객체를 지우고 스티커 행은 남지 않는다")
    void sticker_upload_failure_cleans_uploaded_objects() throws Exception {
        org.mockito.Mockito.when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(null).thenThrow(SdkClientException.create("S3 다운"));

        mvc.perform(upsert("{\"name\":{\"original\":\"실패 세트\"}}", png(8, 8), png(8, 8)))
                .andExpect(status().isInternalServerError());

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(put.capture(), any(RequestBody.class));
        assertThat(deletedKeys()).containsExactlyInAnyOrderElementsOf(put.getAllValues().stream().map(PutObjectRequest::key).toList());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stickers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sticker_sets", Integer.class)).isZero();
    }

    static MockMultipartHttpServletRequestBuilder create(long userId, byte[] image, String filename, String meta) {
        String contentType = filename.endsWith(".jpg") ? "image/jpeg" : "image/png";
        return (MockMultipartHttpServletRequestBuilder) multipart(HttpMethod.POST, "/api/v1/photocards")
                .file(new MockMultipartFile("image", filename, contentType, image))
                .part(jsonPart("meta", meta))
                .with(user(userId));
    }

    private static MockHttpServletRequestBuilder relink(long userId, long photocardId, String body) {
        return patch("/api/v1/photocards/{id}", photocardId).with(user(userId)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockMultipartHttpServletRequestBuilder upsert(String meta, byte[]... files) {
        MockMultipartHttpServletRequestBuilder builder = multipart(HttpMethod.PUT, "/api/v1/admin/sticker-sets").part(jsonPart("meta", meta));
        for (int i = 0; i < files.length; i++) {
            builder.file(new MockMultipartFile("stickers", "sticker" + i + ".png", "image/png", files[i]));
        }
        return (MockMultipartHttpServletRequestBuilder) builder.with(admin());
    }

    // 브라우저가 new Blob([json], {type: 'application/json'}) 을 FormData 에 넣은 것과 같은 파트
    static MockPart jsonPart(String name, String json) {
        return new MockPart(name, "blob", json.getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON);
    }

    // 관리자: ADMIN 권한 + 동의한 users 행(쓰기 요청은 동의 게이트를 지난다)
    static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ADMIN, true), null,
                List.of(new SimpleGrantedAuthority("ADMIN"))));
    }

    // 4:5 카드 이미지
    static byte[] card() throws IOException {
        return png(8, 10);
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

    static DocumentContext json(MvcResult result) throws Exception {
        return JsonPath.parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
