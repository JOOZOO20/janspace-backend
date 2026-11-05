package com.stagelog.media.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.media.application.RenditionRetry;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 미디어 asset 진단 조회(current 판정, rendition 6개, Outbox 본문 renditionId 로 연결한 Queue 실패 기록)와
 * FAILED rendition 재변환(PENDING·시도 0·Outbox 재발행·asset 재집계·감사 로그 한 트랜잭션).
 */
class AdminMediaAssetIntegrationTest extends IntegrationTest {

    static final long EVENT_ID = 970_001L;
    static final String CURRENT_KEY = "posters/970001/current.jpg";

    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RenditionRetry retry;

    MockMvc mvc;
    long failedRendition;
    long readyRendition;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("media_renditions", "media_assets", "outbox_events", "queue_message_failures", "admin_audit_logs")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.update("DELETE FROM events WHERE id = ?", EVENT_ID);
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("INSERT INTO events (id, name, original_locale, event_date, timezone, type, status, poster_key, locked_fields, "
                + "created_at, updated_at) VALUES (?, '미디어 진단 공연', 'ko', '2026-12-01', 'Asia/Seoul', 'CONCERT', 'ACTIVE', ?, '[]', "
                + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", EVENT_ID, CURRENT_KEY);
        // 예전 원본 asset 1 (current 아님) + 현재 원본 asset 2 (list webp 만 FAILED, 나머지 READY)
        jdbc.update("INSERT INTO media_assets (id, owner_type, owner_id, original_key, original_sha256, width, height, mime_type, status, "
                + "created_at, updated_at) VALUES (1, 'EVENT_POSTER', ?, 'posters/970001/old.jpg', REPEAT('a', 64), 800, 1000, 'image/jpeg', "
                + "'READY', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)), (2, 'EVENT_POSTER', ?, ?, REPEAT('b', 64), 1600, 2000, 'image/jpeg', 'PARTIAL', "
                + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", EVENT_ID, EVENT_ID, CURRENT_KEY);
        long id = 20;
        for (String profile : List.of("thumb", "list", "detail")) {
            for (String format : List.of("webp", "jpeg")) {
                boolean failed = profile.equals("list") && format.equals("webp");
                jdbc.update("INSERT INTO media_renditions (id, asset_id, profile, format, output_key, bytes, status, attempt, last_error, "
                        + "created_at, updated_at) VALUES (?, 2, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", ++id, profile, format,
                        failed ? null : "media/2/" + profile + "-00000000." + format, failed ? null : 1000L, failed ? "FAILED" : "READY",
                        failed ? 3 : 1, failed ? "FFMPEG_FAILED: exit=1" : null);
                if (failed) {
                    failedRendition = id;
                } else {
                    readyRendition = id;
                }
            }
        }
        jdbc.update("INSERT INTO outbox_events (message_id, queue_name, payload, status, attempts, created_at, updated_at) "
                + "VALUES ('11111111-1111-4111-8111-111111111111', 'jan-media-transcode', ?, 'PUBLISHED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                "{\"messageId\":\"11111111-1111-4111-8111-111111111111\",\"renditionId\":" + failedRendition + "}");
        jdbc.update("INSERT INTO queue_message_failures (message_id, queue_name, attempts, last_error, permanent, last_failed_at, created_at, "
                + "updated_at) VALUES ('11111111-1111-4111-8111-111111111111', 'jan-media-transcode', 3, 'TranscodeException: FFMPEG_FAILED', "
                + "TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    @DisplayName("소유자의 asset 최신순: 현재 원본만 current, rendition 6개, 실패 rendition 에만 Queue 실패 기록이 붙는다")
    void lists_assets_with_renditions_and_failures() throws Exception {
        mvc.perform(get("/api/v1/admin/media/assets").param("ownerType", "EVENT_POSTER").param("ownerId", String.valueOf(EVENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(2))
                .andExpect(jsonPath("$.data[0].current").value(true))
                .andExpect(jsonPath("$.data[0].status").value("PARTIAL"))
                .andExpect(jsonPath("$.data[0].originalKey").value(CURRENT_KEY))
                .andExpect(jsonPath("$.data[0].renditions.length()").value(6))
                .andExpect(jsonPath("$.data[0].renditions[2].profile").value("list"))
                .andExpect(jsonPath("$.data[0].renditions[2].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].renditions[2].lastError").value("FFMPEG_FAILED: exit=1"))
                .andExpect(jsonPath("$.data[0].renditions[2].queueFailures[0].messageId").value("11111111-1111-4111-8111-111111111111"))
                .andExpect(jsonPath("$.data[0].renditions[2].queueFailures[0].permanent").value(true))
                .andExpect(jsonPath("$.data[0].renditions[0].queueFailures.length()").value(0))
                .andExpect(jsonPath("$.data[1].current").value(false));
        mvc.perform(get("/api/v1/admin/media/assets").param("ownerType", "ALBUM").param("ownerId", "1"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("재변환: FAILED 만 PENDING·시도 0 으로 돌리고 jan-media-transcode 에 다시 발행하며 asset 을 다시 집계하고 감사 로그를 남긴다")
    void retry_failed_rendition() {
        retry.retryFailed(failedRendition, AuditActor.admin(9L));

        Map<String, Object> row = jdbc.queryForMap("SELECT status, attempt FROM media_renditions WHERE id = ?", failedRendition);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt")).intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM media_assets WHERE id = 2", String.class)).isEqualTo("PENDING");
        // MySQL JSON 컬럼은 공백을 넣어 다시 쓰므로 문자열 비교 대신 JSON_EXTRACT 로 본다 (테스트 검증용)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE queue_name = 'jan-media-transcode' AND status = 'PENDING' "
                + "AND JSON_EXTRACT(payload, '$.renditionId') = ?", Integer.class, failedRendition)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT CONCAT(action, ':', target_id, ':', actor_id) FROM admin_audit_logs", String.class))
                .isEqualTo("MEDIA_RENDITION_RETRY:" + failedRendition + ":9");

        assertThatThrownBy(() -> retry.retryFailed(readyRendition, AuditActor.admin(9L))).isInstanceOf(ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs", Integer.class)).isEqualTo(1);
    }
}
