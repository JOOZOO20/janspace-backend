package com.stagelog.media;

import com.stagelog.ops.application.AuditActor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.EventCommand;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.media.application.ImageSet;
import com.stagelog.media.application.MediaAssets;
import com.stagelog.media.application.MediaImages;
import com.stagelog.media.application.MediaOwnerType;
import com.stagelog.media.application.RenditionRecovery;
import com.stagelog.media.domain.MediaRendition;
import com.stagelog.media.domain.MediaRenditionRepository;
import com.stagelog.support.FfmpegContainer;
import com.stagelog.support.LocalStackIntegrationTest;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JAN-05 변환 파이프라인 수용 기준 (LocalStack S3·SQS + Dockerfile runtime stage 의 FFmpeg). 앱의 polling thread 가 실제 SQS 에서 받아 처리한다.
 * Zombie 회수는 테스트가 RenditionRecovery 를 직접 부른다(스케줄러 주기는 길게 둔다).
 */
@TestPropertySource(properties = {
        "jan.media.transcode.enabled=true",
        "jan.media.recovery.interval=1h",
        "jan.media.backfill.rate-per-second=50",
        "jan.queue.consumer.wait-seconds=1",
        "jan.queue.backoff-seconds=1,1,1,1"})
class MediaPipelineIntegrationTest extends LocalStackIntegrationTest {

    static final long ADMIN_ID = 906L;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration WAIT = Duration.ofSeconds(90);

    @DynamicPropertySource
    static void ffmpeg(DynamicPropertyRegistry registry) {
        registry.add("jan.media.ffmpeg.command", () -> FfmpegContainer.commandProperty("ffmpeg"));
        registry.add("jan.media.ffmpeg.probe-command", () -> FfmpegContainer.commandProperty("ffprobe"));
    }

    @Autowired
    WebApplicationContext web;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    EventRepository events;
    @Autowired
    EventCommand eventCommand;
    @Autowired
    MediaAssets mediaAssets;
    @Autowired
    MediaImages mediaImages;
    @Autowired
    MediaRenditionRepository renditions;
    @Autowired
    RenditionRecovery recovery;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("포스터 업로드 -> rendition 6개 READY, asset READY, 응답 images 가 rendition URL 이고 객체는 immutable 캐시")
    void upload_becomes_ready_and_served() throws Exception {
        long eventId = newEvent();
        String posterUrl = eventCommand.uploadPoster(eventId, jpeg(1600, 2000, 1), "image/jpeg", AuditActor.system("test"));

        await(() -> assetStatus(eventId).equals("READY"));
        List<MediaRendition> rows = renditionsOf(eventId);
        assertThat(rows).hasSize(6).allSatisfy(r -> {
            assertThat(r.getStatus().name()).isEqualTo("READY");
            assertThat(r.getAttempt()).isEqualTo(1);
            assertThat(r.getOutputKey()).matches("media/\\d+/(thumb|list|detail)-[0-9a-f]{8}\\.(webp|jpg)");
        });
        assertThat(rows).extracting(MediaRendition::getProfile, MediaRendition::getFormat, MediaRendition::getWidth)
                .contains(org.assertj.core.groups.Tuple.tuple("thumb", "webp", 256), org.assertj.core.groups.Tuple.tuple("detail", "jpeg", 1152));

        JsonNode images = eventJson(eventId).path("images");
        for (String profile : List.of("thumb", "list", "detail")) {
            assertThat(images.path(profile).path("webp").asString()).endsWith(".webp").startsWith(bucketUrl() + "/media/");
            assertThat(images.path(profile).path("jpeg").asString()).endsWith(".jpg");
        }
        assertThat(eventJson(eventId).path("posterUrl").asString()).isEqualTo(posterUrl);
        try (S3Client s3 = adminS3()) {
            String key = rows.get(0).getOutputKey();
            assertThat(s3.headObject(b -> b.bucket(BUCKET).key(key)).cacheControl()).isEqualTo("public, max-age=31536000, immutable");
        }
    }

    @Test
    @DisplayName("rendition 이 끝나지 않은 동안 images 의 모든 자리는 원본 URL")
    void unfinished_renditions_fall_back_to_original() throws IOException {
        long eventId = newEvent();
        byte[] original = jpeg(800, 1000, 2);
        // 등록과 같은 트랜잭션 안: Worker 는 커밋 전 행을 볼 수 없으므로 모든 rendition 이 PENDING 이다
        ImageSet images = transactions.execute(status -> {
            mediaAssets.register(MediaOwnerType.EVENT_POSTER, eventId, "events/pending/original.jpg", original);
            return mediaImages.of(MediaOwnerType.EVENT_POSTER,
                    new MediaImages.Original(eventId, "events/pending/original.jpg", "https://cdn.example/events/pending/original.jpg"));
        });
        assertThat(List.of(images.thumb(), images.list(), images.detail())).allSatisfy(v -> {
            assertThat(v.webp()).isEqualTo("https://cdn.example/events/pending/original.jpg");
            assertThat(v.jpeg()).isEqualTo("https://cdn.example/events/pending/original.jpg");
        });
    }

    @Test
    @DisplayName("잘린 JPEG·확장자 위장·초대형 해상도: FFmpeg 전에 FAILED(INVALID_SOURCE), attempt 1, 메시지 삭제(영구 실패 기록), 응답은 원본 URL")
    void invalid_sources_fail_without_retry() throws Exception {
        byte[] valid = jpeg(400, 300, 3);
        byte[] truncated = Arrays.copyOf(valid, valid.length / 2);
        byte[] disguised = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
        byte[] huge = hugeHeaderPng();
        for (byte[] source : List.of(truncated, disguised, huge)) {
            long eventId = newEvent();
            String posterUrl = eventCommand.uploadPoster(eventId, source, "image/jpeg", AuditActor.system("test"));
            await(() -> assetStatus(eventId).equals("FAILED"));
            assertThat(renditionsOf(eventId)).hasSize(6).allSatisfy(r -> {
                assertThat(r.getStatus().name()).isEqualTo("FAILED");
                assertThat(r.getAttempt()).isEqualTo(1);
                assertThat(r.getLastError()).startsWith("INVALID_SOURCE");
            });
            JsonNode images = eventJson(eventId).path("images");
            assertThat(images.path("list").path("webp").asString()).isEqualTo(posterUrl);
            assertThat(images.path("detail").path("jpeg").asString()).isEqualTo(posterUrl);
        }
        await(() -> count("SELECT COUNT(*) FROM queue_message_failures WHERE queue_name = 'jan-media-transcode' AND permanent = TRUE") >= 18);
        assertThat(count("SELECT COUNT(*) FROM queue_message_failures WHERE queue_name = 'jan-media-transcode' AND attempts > 1")).isZero();
    }

    @Test
    @DisplayName("투명 PNG 는 webp + png rendition 을 만들고 images.jpeg 자리에 PNG URL")
    void transparent_png_gets_png_fallback() throws Exception {
        BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillOval(100, 100, 400, 400);
        g.dispose();
        long eventId = newEvent();
        eventCommand.uploadPoster(eventId, encode(image, "png"), "image/png", AuditActor.system("test"));
        await(() -> assetStatus(eventId).equals("READY"));
        assertThat(renditionsOf(eventId)).extracting(MediaRendition::getFormat).containsOnly("webp", "png");
        assertThat(eventJson(eventId).path("images").path("thumb").path("jpeg").asString()).endsWith(".png");
    }

    @Test
    @DisplayName("Zombie 회수: Lease 가 만료된 PROCESSING 은 PENDING + 재발행으로 다시 READY, 시도 횟수를 다 쓴 행은 FAILED 이고 asset 은 PARTIAL")
    void zombie_recovery() throws Exception {
        long eventId = newEvent();
        eventCommand.uploadPoster(eventId, jpeg(900, 900, 4), "image/jpeg", AuditActor.system("test"));
        await(() -> assetStatus(eventId).equals("READY"));
        List<MediaRendition> rows = renditionsOf(eventId);
        long revived = rows.get(0).getId();
        long exhausted = rows.get(1).getId();
        // Worker 가 처리 중에 죽은 상태: PROCESSING, 다른 소유자의 만료된 Lease
        jdbc.update("UPDATE media_renditions SET status = 'PROCESSING', lease_owner = 'dead', lease_expires_at = NOW(6) - INTERVAL 1 SECOND,"
                + " attempt = 1 WHERE id = ?", revived);
        jdbc.update("UPDATE media_renditions SET status = 'PROCESSING', lease_owner = 'dead', lease_expires_at = NOW(6) - INTERVAL 1 SECOND,"
                + " attempt = 5 WHERE id = ?", exhausted);

        assertThat(recovery.recover()).isEqualTo(1);
        await(() -> renditions.findById(revived).orElseThrow().getStatus().name().equals("READY"));
        assertThat(renditions.findById(revived).orElseThrow().getAttempt()).isEqualTo(2);
        MediaRendition failed = renditions.findById(exhausted).orElseThrow();
        assertThat(failed.getStatus().name()).isEqualTo("FAILED");
        assertThat(failed.getLastError()).startsWith("ZOMBIE");
        assertThat(assetStatus(eventId)).isEqualTo("PARTIAL");
        // 처리 중(Lease 유효)인 행은 건드리지 않는다
        assertThat(recovery.recover()).isZero();
    }

    @Test
    @DisplayName("백필: 기존 원본을 등록·발행하고, 다시 시작하면 이미 등록된 소유자는 건너뛰어 이어서 진행한다")
    void backfill_resumes() throws Exception {
        try (S3Client s3 = adminS3()) {
            s3.putObject(b -> b.bucket(BUCKET).key("events/backfill-it/a.jpg").contentType("image/jpeg"), RequestBody.fromBytes(jpeg(700, 900, 5)));
        }
        List<Long> ids = List.of(newEvent(), newEvent(), newEvent());
        for (Long id : ids) {
            jdbc.update("UPDATE events SET poster_key = 'events/backfill-it/a.jpg' WHERE id = ?", id);
        }
        // 다른 테스트가 남긴 포스터 행사는 이 테스트 범위 밖이므로 지운다
        jdbc.update("UPDATE events SET poster_key = NULL WHERE id NOT IN (?, ?, ?)", ids.get(0), ids.get(1), ids.get(2));

        JsonNode first = backfill("{\"ownerType\":\"EVENT_POSTER\",\"limit\":2}");
        assertThat(first.path("running").asBoolean()).isTrue();
        await(() -> !backfillStatus().path("running").asBoolean());
        assertThat(backfillStatus().path("registered").asInt()).isEqualTo(2);

        backfill("{\"ownerType\":\"EVENT_POSTER\",\"limit\":10}");
        await(() -> !backfillStatus().path("running").asBoolean());
        JsonNode second = backfillStatus();
        assertThat(second.path("registered").asInt()).isEqualTo(1);
        assertThat(second.path("skipped").asInt()).isEqualTo(2);
        for (Long id : ids) {
            await(() -> assetStatus(id).equals("READY"));
        }
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE queue_name = 'jan-media-backfill'")).isEqualTo(18);
    }

    @Test
    @DisplayName("같은 메시지를 다시 받으면(claim 0) 결과를 바꾸지 않고 삭제한다")
    void duplicate_delivery_is_ignored() throws Exception {
        long eventId = newEvent();
        eventCommand.uploadPoster(eventId, jpeg(500, 500, 6), "image/jpeg", AuditActor.system("test"));
        await(() -> assetStatus(eventId).equals("READY"));
        MediaRendition before = renditionsOf(eventId).get(0);
        // 같은 rendition 메시지를 한 번 더 발행
        transactions.executeWithoutResult(s -> jdbc.update("INSERT INTO outbox_events (message_id, queue_name, payload, status, attempts, created_at,"
                + " updated_at) VALUES (UUID(), 'jan-media-transcode', JSON_OBJECT('renditionId', ?), 'PENDING', 0, NOW(6), NOW(6))", before.getId()));
        await(() -> count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'") == 0);
        Thread.sleep(3000);
        MediaRendition after = renditions.findById(before.getId()).orElseThrow();
        assertThat(after.getAttempt()).isEqualTo(1);
        assertThat(after.getOutputKey()).isEqualTo(before.getOutputKey());
    }

    private JsonNode backfill(String body) throws Exception {
        insertAdmin();
        var result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/admin/media/backfill").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private JsonNode backfillStatus() {
        try {
            insertAdmin();
            var result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/admin/media/backfill").with(admin())).andReturn();
            return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ADMIN_ID, true), null,
                List.of(new SimpleGrantedAuthority("ADMIN"))));
    }

    private void insertAdmin() {
        jdbc.update("INSERT IGNORE INTO users (id, nickname, locale, is_admin, consented_at, terms_version, created_at, updated_at) "
                + "VALUES (?, 'admin906', 'ko', TRUE, UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", ADMIN_ID);
    }

    private JsonNode eventJson(long eventId) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/events/" + eventId)).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private long newEvent() {
        return transactions.execute(s -> events.save(Event.create("미디어 " + System.nanoTime(), SupportedLocale.KO, EventType.CONCERT,
                LocalDate.of(2027, 3, 1))).getId());
    }

    private String assetStatus(long eventId) {
        List<String> rows = jdbc.queryForList("SELECT status FROM media_assets WHERE owner_type = 'EVENT_POSTER' AND owner_id = ? ORDER BY id DESC",
                String.class, eventId);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    private List<MediaRendition> renditionsOf(long eventId) {
        Long assetId = jdbc.queryForObject("SELECT MAX(id) FROM media_assets WHERE owner_type = 'EVENT_POSTER' AND owner_id = ?", Long.class, eventId);
        return renditions.findAllByAssetIdOrderById(assetId);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("조건을 " + WAIT.toSeconds() + "초 안에 만족하지 못했다");
            }
            Thread.sleep(200);
        }
    }

    // 사진처럼 노이즈가 있는 JPEG. seed 가 다르면 내용(sha256)이 다르다
    static byte[] jpeg(int width, int height, long seed) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int base = (x * 255 / width) << 16 | (y * 255 / height) << 8;
                image.setRGB(x, y, base | random.nextInt(64));
            }
        }
        return encode(image, "jpg");
    }

    static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    // IHDR 이 100000x100000 을 선언하는 작은 PNG (decompression bomb)
    static byte[] hugeHeaderPng() throws IOException {
        byte[] png = encode(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png");
        java.nio.ByteBuffer.wrap(png).putInt(16, 100_000).putInt(20, 100_000);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(png, 12, 17);
        java.nio.ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        return png;
    }
}
