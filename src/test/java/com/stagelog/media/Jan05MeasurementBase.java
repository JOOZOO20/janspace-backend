package com.stagelog.media;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.EventCommand;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.media.application.port.ImageTranscoderPort;
import com.stagelog.media.domain.RenditionFormat;
import com.stagelog.media.infrastructure.FfmpegImageTranscoder;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.support.FfmpegContainer;
import com.stagelog.support.LocalStackIntegrationTest;
import com.stagelog.support.ProjectPaths;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * JAN-05 측정 공통 기반 (docs/measurements/JAN-05.md). 수정 전 코드에도 있는 공개 진입점(포스터 업로드 EventCommand.uploadPoster,
 * 행사 목록 API, 관리자 API URL)과 DB·S3·SQS 를 직접 보고, 수정 후에만 있는 것(media 테이블, 변환 Worker 빈 mediaTranscodeHandler,
 * 백필 API)은 이름으로 찾는다(없으면 N/A). FFmpeg 는 저장소 Dockerfile 의 runtime stage 컨테이너를 docker exec 로 부른다.
 * 변환 엔진 포트(ImageTranscoderPort)를 감싸 Worker 강제 종료(S2)를 재현한다. 수치만 출력하고 assert 하지 않는다.
 */
@Slf4j
@Tag("measurement")
@Import(Jan05MeasurementBase.KillSwitchConfig.class)
@TestPropertySource(properties = {
        "jan.media.transcode.enabled=true",
        "jan.queue.consumer.wait-seconds=1"})
abstract class Jan05MeasurementBase extends LocalStackIntegrationTest {

    static final String HANDLER_BEAN = "mediaTranscodeHandler";
    static final String TRANSCODE_QUEUE = "jan-media-transcode";
    static final String BACKFILL_QUEUE = "jan-media-backfill";
    static final long ADMIN_ID = 905L;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    // 강제 종료 대상(원본 sha256) -> 종료 시각
    static final Set<String> KILL_TARGETS = ConcurrentHashMap.newKeySet();
    static final Map<String, Instant> KILLED_AT = new ConcurrentHashMap<>();

    /** 처리 중 프로세스 비정상 종료 재현. Error 라 Worker 와 Consumer 가 잡지 않고 Lease 반납·메시지 삭제·완료 없이 빠진다. */
    static final class SimulatedKill extends Error {
        SimulatedKill() {
            super("JAN-05 측정: Worker 강제 종료 재현");
        }
    }

    @DynamicPropertySource
    static void ffmpegProperties(DynamicPropertyRegistry registry) {
        registry.add("jan.media.ffmpeg.command", () -> FfmpegContainer.commandProperty("ffmpeg"));
        registry.add("jan.media.ffmpeg.probe-command", () -> FfmpegContainer.commandProperty("ffprobe"));
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    WebApplicationContext web;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    EventCommand eventCommand;

    MockMvc mvc;

    final SqsClient sqs = SqsClient.builder()
            .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
            .build();

    @BeforeEach
    void setUpBase() {
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
    }

    /**
     * 변환 엔진 포트를 감싸 강제 종료를 재현한다. 대상 원본이면 실제 FFmpeg 를 띄운 직후 Error 로 빠진다(FFmpeg 는 고아 프로세스로 끝까지 돈다).
     * Mockito spy 는 호출 인자(원본 bytes)를 모두 기록해 측정 중 heap 을 채우므로 쓰지 않는다.
     */
    @TestConfiguration
    static class KillSwitchConfig {

        @Bean
        @Primary
        ImageTranscoderPort killableTranscoder(FfmpegImageTranscoder real) {
            return new ImageTranscoderPort() {
                @Override
                public byte[] transcode(byte[] source, int orientation, int width, int height, RenditionFormat format, int quality, boolean alpha) {
                    String sha = sha256(source);
                    if (KILL_TARGETS.remove(sha)) {
                        Thread.ofVirtual().start(() -> {
                            try {
                                real.transcode(source, orientation, width, height, format, quality, alpha);
                            } catch (RuntimeException ignored) {
                                // 버려진 변환. 결과는 쓰지 않는다
                            }
                        });
                        sleep(200);
                        KILLED_AT.put(sha, Instant.now());
                        throw new SimulatedKill();
                    }
                    return real.transcode(source, orientation, width, height, format, quality, alpha);
                }

                @Override
                public Probe probe(byte[] image) {
                    return real.probe(image);
                }
            };
        }
    }

    static final int S3_NEW = 100;
    static final int BACKFILL_SIZE = 10_000;
    // 백필 발행이 쌓이도록 신규 업로드 전에 기다리는 시간
    static final long BACKLOG_SECONDS = 30;
    // 신규 원본 도착 간격 (시나리오 조건. 백필 없을 때 Worker 처리량 안쪽)
    static final long ARRIVAL_MILLIS = 3000;
    // 마지막 신규 업로드 뒤 기다리는 상한. 넘기면 미완료로 센다
    static final long CAP_SECONDS = 300;
    static final String SEED_KEY = "events/jan05-backfill/seed.jpg";

    /**
     * S3 한 회차: (백필 1만 건 시작 -> 30초 대기) -> 신규 원본 100건을 ARRIVAL_MILLIS 간격으로 업로드 -> 모두 끝나거나 상한까지 대기.
     * 처리 지연 = media_assets.created_at -> 그 asset 의 rendition 6개가 모두 READY 인 마지막 updated_at (둘 다 DB 시계).
     */
    void backfillScenario(String label, boolean withBackfill, int run) throws Exception {
        if (!pipelinePresent()) {
            log.info("[JAN-05 측정 S3] 조건={}, run={}: N/A (변환 Worker {} 가 없다)", label, run, HANDLER_BEAN);
            return;
        }
        List<Jan05Samples.Sample> posters = Jan05Samples.load().subList(0, 50);
        resetData();
        if (withBackfill) {
            // 1. 기존 원본 1만 건: 같은 S3 원본을 가리키는 과거 행사 (asset 은 소유자마다 따로 생긴다)
            try (S3Client s3 = adminS3()) {
                s3.putObject(b -> b.bucket(BUCKET).key(SEED_KEY).contentType("image/jpeg"), RequestBody.fromBytes(posters.get(0).bytes()));
            }
            String digits = "(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5"
                    + " UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
            jdbc.update("INSERT INTO events (name, original_locale, event_date, timezone, type, status, locked_fields, poster_key, created_at, updated_at)"
                    + " SELECT CONCAT('BF-', a.n + b.n * 10 + c.n * 100 + d.n * 1000), 'ko', '2020-01-01', 'Asia/Seoul', 'CONCERT', 'ACTIVE',"
                    + " JSON_ARRAY(), ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM " + digits + " a, " + digits + " b, " + digits + " c, " + digits + " d",
                    SEED_KEY);
            AdminResult started = admin("POST", "/api/v1/admin/media/backfill",
                    "{\"ownerType\":\"EVENT_POSTER\",\"limit\":" + BACKFILL_SIZE + "}");
            if (started.status() != 202) {
                log.info("[JAN-05 측정 S3] 조건={}, run={}: N/A (백필 API 응답 {})", label, run, started.status());
                return;
            }
            sleep(BACKLOG_SECONDS * 1000);
        }
        // 2. 신규 원본 100건
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < S3_NEW; i++) {
            Jan05Samples.Sample s = posters.get(i % posters.size());
            ids.add(newEventWithPoster("S3-" + i, LocalDate.of(2028, 1, 1).plusDays(i), s.bytes(), s.contentType()));
            sleep(ARRIVAL_MILLIS);
        }
        // 3. 모두 끝나거나 상한까지
        long deadline = System.nanoTime() + CAP_SECONDS * 1_000_000_000L;
        Map<Long, Long> latencies = latenciesMillis(ids);
        while (latencies.containsValue(null) && System.nanoTime() < deadline) {
            sleep(1000);
            latencies = latenciesMillis(ids);
        }
        String queues = "transcode=" + depth(TRANSCODE_QUEUE) + ", backfill=" + depth(BACKFILL_QUEUE);
        Integer backfilled = null;
        if (withBackfill) {
            AdminResult stopped = admin("DELETE", "/api/v1/admin/media/backfill", null);
            backfilled = stopped.body().path("data").path("registered").asInt();
            for (int i = 0; i < 20 && admin("GET", "/api/v1/admin/media/backfill", null).body().path("data").path("running").asBoolean(); i++) {
                sleep(500);
            }
        }
        long incomplete = latencies.values().stream().filter(java.util.Objects::isNull).count();
        List<Long> done = latencies.values().stream().filter(java.util.Objects::nonNull).sorted().toList();
        log.info("[JAN-05 측정 S3] 조건={}, run={}, 신규={}, 완료={}, 미완료={}, 지연 ms p50={}, p95={}, max={}, 백필 등록={}, 종료 시 Queue 메시지 수({})",
                label, run, ids.size(), done.size(), incomplete, percentile(latencies.values(), 50), percentile(latencies.values(), 95),
                done.isEmpty() ? null : done.get(done.size() - 1), backfilled, queues);
        // 4. 다음 회차에 남은 메시지·처리 중 작업이 섞이지 않게
        purgeQueues();
        sleep(5000);
    }

    // SQS ApproximateNumberOfMessages (보이는 메시지). Queue 가 없으면 -1
    int depth(String queue) {
        try {
            String url = sqs.getQueueUrl(b -> b.queueName(queue)).queueUrl();
            return Integer.parseInt(sqs.getQueueAttributes(b -> b.queueUrl(url).attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    .attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    boolean pipelinePresent() {
        return context.containsBean(HANDLER_BEAN);
    }

    boolean tableExists(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
        return n != null && n > 0;
    }

    // 측정 사이 초기화. 행사·미디어·Outbox·실패 기록만 지운다 (S3 객체는 내용 hash key 라 남겨도 결과가 같다).
    // TRUNCATE 가 아니라 DELETE 라 AUTO_INCREMENT 가 이어져, 한 JVM 안에서 asset id(출력 key 의 경로)가 겹치지 않는다
    void resetData() {
        List<String> tables = new ArrayList<>(List.of("media_renditions", "media_assets", "outbox_events", "queue_message_failures",
                "event_translations", "event_sources", "events"));
        tables.removeIf(t -> !tableExists(t));
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : tables) {
                    statement.execute("DELETE FROM " + table);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        purgeQueues();
        KILL_TARGETS.clear();
        KILLED_AT.clear();
    }

    void purgeQueues() {
        for (String name : List.of(TRANSCODE_QUEUE, BACKFILL_QUEUE, TRANSCODE_QUEUE + "-dlq", BACKFILL_QUEUE + "-dlq")) {
            try {
                String url = sqs.getQueueUrl(b -> b.queueName(name)).queueUrl();
                sqs.purgeQueue(b -> b.queueUrl(url));
            } catch (RuntimeException ignored) {
                // Queue 가 없다 (수정 전 또는 분리를 끈 실행)
            }
        }
    }

    // 행사 1개 + 포스터 업로드 (수정 후에는 같은 트랜잭션에서 변환 asset 이 등록된다)
    long newEventWithPoster(String name, LocalDate date, byte[] poster, String contentType) {
        Long id = transactions.execute(status ->
                eventRepository.save(Event.create(name, SupportedLocale.KO, EventType.CONCERT, date)).getId());
        eventCommand.uploadPoster(id, poster, contentType, AuditActor.system("test"));
        return id;
    }

    // 행사 목록 API (UPCOMING, 20개씩) 의 content
    List<JsonNode> listPage(int page) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/events").param("tab", "UPCOMING")
                .param("page", String.valueOf(page)).param("size", "20")).andReturn();
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        List<JsonNode> items = new ArrayList<>();
        body.path("data").path("content").forEach(items::add);
        return items;
    }

    // 관리자 API 호출. 상태 코드와 본문
    record AdminResult(int status, JsonNode body) {
    }

    AdminResult admin(String method, String path, String json) throws Exception {
        jdbc.update("INSERT IGNORE INTO users (id, nickname, locale, is_admin, consented_at, terms_version, created_at, updated_at) "
                + "VALUES (?, 'jan05admin', 'ko', TRUE, UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", ADMIN_ID);
        var request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path)
                .with(authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ADMIN_ID, true), null,
                        List.of(new SimpleGrantedAuthority("ADMIN")))));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        MvcResult result = mvc.perform(request).andReturn();
        String text = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return new AdminResult(result.getResponse().getStatus(), text.isBlank() ? null : JSON.readTree(text));
    }

    static long httpSize(String url) {
        try {
            HttpResponse<byte[]> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("GET " + response.statusCode() + " " + url);
            }
            return response.body().length;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // 소유자 행사들의 asset 별 처리 지연(DB 시계: created_at -> rendition 모두 READY 의 마지막 updated_at). 끝나지 않은 asset 은 null
    Map<Long, Long> latenciesMillis(Collection<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        String in = eventIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        Map<Long, Long> result = new java.util.HashMap<>();
        jdbc.query("""
                SELECT a.owner_id AS owner_id, SUM(r.status = 'READY') AS ready, COUNT(*) AS total,
                       TIMESTAMPDIFF(MICROSECOND, a.created_at, MAX(r.updated_at)) AS us
                  FROM media_assets a JOIN media_renditions r ON r.asset_id = a.id
                 WHERE a.owner_type = 'EVENT_POSTER' AND a.owner_id IN (""" + in + ") GROUP BY a.id, a.owner_id, a.created_at",
                rs -> {
                    long owner = rs.getLong("owner_id");
                    boolean done = rs.getInt("ready") == rs.getInt("total");
                    result.put(owner, done ? rs.getLong("us") / 1000 : null);
                });
        eventIds.forEach(id -> result.putIfAbsent(id, null));
        return result;
    }

    // p 백분위 (nearest-rank). 끝나지 않은 값(null)은 무한대로 보고, 그 순위에 걸리면 null
    static Long percentile(Collection<Long> values, double p) {
        List<Long> sorted = values.stream().map(v -> v == null ? Long.MAX_VALUE : v).sorted().toList();
        if (sorted.isEmpty()) {
            return null;
        }
        long value = sorted.get((int) Math.ceil(p / 100.0 * sorted.size()) - 1);
        return value == Long.MAX_VALUE ? null : value;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] jpeg(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path projectFile(String relative) {
        return ProjectPaths.projectRoot().resolve(relative);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void writeLines(Path path, List<String> lines) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
