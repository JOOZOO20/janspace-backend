package com.stagelog.event.application.sync;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.SourceSnapshotCodec;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.Provider;
import com.stagelog.support.LocalStackIntegrationTest;
import com.stagelog.support.ProjectPaths;
import com.sun.net.httpserver.HttpServer;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.ExecutingResponseCreator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * JAN-04 측정 S2·S3·S4 (docs/measurements/JAN-04.md). 수치만 출력하고 assert 하지 않는다. 수정 전 코드에도 있는 공개 진입점
 * (KopisEnrichment.enrich, ApplySourcesUseCase.refold)과 SQS·DB 를 직접 보며, 수정 후에만 있는 Queue·Relay 는 이름으로 찾는다
 * (없으면 N/A). 아래 jan.* 속성은 수정 전 코드에서는 아무 효과가 없다.
 */
@Slf4j
@Tag("measurement")
@TestPropertySource(properties = {
        "jan.pipeline.async-apply.enabled=true",
        // S4 가 "발행 전 종료"를 만들도록 Relay 는 테스트가 직접 돌린다
        "jan.queue.relay.enabled=false",
        "jan.queue.consumer.wait-seconds=1",
        "jan.queue.backoff-seconds=1,1,1,1"})
class Jan04QueueMeasurementTest extends LocalStackIntegrationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    static final String KOPIS = "https://www.kopis.or.kr/openApi/restful";
    static final String KEY = "service=test-kopis-key";
    static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    static final String RELAY = "com.stagelog.ops.application.OutboxRelay";
    static final String APPLY_HANDLER = "com.stagelog.event.application.sync.SourceApplyHandler";
    static final int POSTER_DELAY_MILLIS = 5000;
    static final List<String> TABLES = List.of("event_proposals", "event_source_changes", "event_discoveries",
            "event_match_candidates", "event_sources", "event_translations", "events", "venue_sources",
            "venue_translations", "venues", "sync_runs");

    static final byte[] PNG = png();
    static final HttpServer POSTERS = posterServer();
    static final ToxiproxyContainer TOXIPROXY;
    static final String POSTER_BASE;

    static {
        // Toxiproxy 컨테이너가 호스트(테스트 JVM)의 포스터 서버에 닿게 한다. 컨테이너 시작 전에 열어야 한다
        Testcontainers.exposeHostPorts(POSTERS.getAddress().getPort());
        TOXIPROXY = new ToxiproxyContainer(DockerImageName.parse("shopify/toxiproxy:2.1.4"));
        TOXIPROXY.start();
        try {
            Proxy proxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy("poster", "0.0.0.0:8666", "host.testcontainers.internal:" + POSTERS.getAddress().getPort());
            proxy.toxics().latency("poster-latency", ToxicDirection.DOWNSTREAM, POSTER_DELAY_MILLIS);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        POSTER_BASE = "http://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8666);
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    KopisEnrichment enrichment;
    @Autowired
    ApplySourcesUseCase applySources;
    @Autowired
    SourceSnapshotCodec codec;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    EventSourceRepository sourceRepository;
    @Autowired
    MockRestServiceServer providers;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactions;

    final SqsClient sqs = SqsClient.builder()
            .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
            .build();

    @BeforeEach
    void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("UPDATE job_leases SET owner_id = NULL, expires_at = NULL");
        providers.reset();
    }

    @Test
    void s2_poster_download_delay_does_not_hold_sync() {
        int runs = 10;
        List<Long> millis = new ArrayList<>();
        int postersStored = 0;
        for (int run = 0; run < runs; run++) {
            clean();
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ids.add(newEvent("원래 이름 " + i, TODAY.plusDays(1 + i)).getId());
            }
            stubKopis(2, run);
            providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith(POSTER_BASE + "/")))
                    .andRespond(new ExecutingResponseCreator(new SimpleClientHttpRequestFactory()));

            long started = System.nanoTime();
            enrichment.enrich(TODAY);
            long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            millis.add(took);

            // 보조 지표: 60초 안에 포스터가 S3 key 로 바뀌는가 (수정 후에는 Relay -> 포스터 Consumer 가 처리한다)
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            int stored = 0;
            while (System.nanoTime() < deadline) {
                relayOnce();
                stored = 0;
                for (Long id : ids) {
                    String key = jdbc.queryForObject("SELECT poster_key FROM events WHERE id = ?", String.class, id);
                    stored += key != null && key.startsWith("events/") ? 1 : 0;
                }
                if (stored == ids.size()) {
                    break;
                }
                sleep(500);
            }
            postersStored += stored;
            log.info("[JAN-04 측정 S2] run={}, 동기화ms={}, 포스터S3반영={}/{}", run, took, stored, ids.size());
        }
        List<Long> sorted = new ArrayList<>(millis);
        Collections.sort(sorted);
        log.info("[JAN-04 측정 S2] runs={}, 포스터지연={}ms, 동기화ms 중앙값={}, 최대={}, 최소={}, 5000ms미만={}/{}, 포스터S3반영={}/{}",
                runs, POSTER_DELAY_MILLIS, sorted.get(sorted.size() / 2), sorted.get(sorted.size() - 1), sorted.get(0),
                millis.stream().filter(m -> m < 5000).count(), runs, postersStored, runs * 2);
    }

    @Test
    void s3_duplicated_and_shuffled_apply_messages() {
        if (!present(APPLY_HANDLER)) {
            log.info("[JAN-04 측정 S3] N/A: apply Queue Consumer 가 없다 (Queue 없음)");
            return;
        }
        int rounds = 100;
        String queueUrl = sqs.getQueueUrl(b -> b.queueName("jan-source-apply")).queueUrl();
        Map<String, Object> baseline = fingerprint(seedSources());
        applySources.refold((Long) baseline.remove("id"));
        baseline = fingerprint(((Number) jdbc.queryForObject("SELECT MAX(id) FROM events", Long.class)).longValue());
        log.info("[JAN-04 측정 S3] 기준값={}", baseline);

        Random random = new Random(20261105L);
        int mismatches = 0;
        int delivered = 0;
        for (int round = 0; round < rounds; round++) {
            clean();
            long eventId = seedSources();
            List<String> bodies = new ArrayList<>();
            for (EventSource source : sourceRepository.findAllByEventId(eventId)) {
                bodies.add(applyMessage(source.getProvider(), codec.read(source.getRaw()), eventId));
            }
            // 이전 내용의 KOPIS 메시지 (역순 도착: 새 내용이 이미 저장된 뒤 옛 메시지가 온다)
            bodies.add(applyMessage(Provider.KOPIS, kopis("KOPIS 옛 설명"), eventId));
            List<String> sends = new ArrayList<>();
            for (String body : bodies) {
                int copies = 1 + random.nextInt(3);
                for (int c = 0; c < copies; c++) {
                    sends.add(body);
                }
            }
            Collections.shuffle(sends, random);
            sends.forEach(body -> sqs.sendMessage(b -> b.queueUrl(queueUrl).messageBody(body)));
            delivered += sends.size();
            boolean drained = awaitDrained(queueUrl, 60);

            Map<String, Object> result = fingerprint(eventId);
            result.remove("id");
            Map<String, Object> expected = new java.util.LinkedHashMap<>(baseline);
            expected.remove("id");
            boolean same = drained && expected.equals(result);
            mismatches += same ? 0 : 1;
            log.info("[JAN-04 측정 S3] round={}, 전달={}, 비움={}, 일치={}{}", round, sends.size(), drained, same,
                    same ? "" : ", 결과=" + result);
        }
        log.info("[JAN-04 측정 S3] rounds={}, 전달 메시지={}, 결과불일치={}/{}", rounds, delivered, mismatches, rounds);
    }

    @Test
    void s4_server_dies_before_relay_publishes() {
        if (!present(RELAY)) {
            log.info("[JAN-04 측정 S4] N/A: Outbox Relay 가 없다 (Queue 없음)");
            return;
        }
        int rounds = 50;
        int lost = 0;
        int pendingTotal = 0;
        for (int round = 0; round < rounds; round++) {
            clean();
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ids.add(newEvent("원래 이름 " + i, TODAY.plusDays(1 + i)).getId());
            }
            stubKopis(2, -1);

            // 1. 출처 저장 + Outbox 기록 커밋. Relay 는 돌지 않는다 = 발행 전에 서버가 죽은 상태
            enrichment.enrich(TODAY);
            Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'", Integer.class);
            pendingTotal += pending == null ? 0 : pending;

            // 2. 재기동: Relay 실행 -> Consumer 처리
            relayOnce();
            int missing = awaitRenamed(ids, 30);
            lost += missing;
            log.info("[JAN-04 측정 S4] round={}, 발행전PENDING={}, 유실={}/{}", round, pending, missing, ids.size());
        }
        log.info("[JAN-04 측정 S4] rounds={}, 발행전PENDING 합계={}, 메시지 유실={}/{}", rounds, pendingTotal, lost, rounds * 2);
    }

    @Test
    void s4_kill_minus_9_child_process_smoke() throws Exception {
        if (!present(RELAY)) {
            log.info("[JAN-04 측정 S4 smoke] N/A: Outbox Relay 가 없다 (Queue 없음)");
            return;
        }
        Path jar = bootJar();
        Assumptions.assumeTrue(jar != null, "build/libs 에 bootJar 가 없다. ./gradlew bootJar 후 실행");
        // 자식 프로세스는 실제 오늘 날짜로 보강 범위를 정하므로 행사도 실제 오늘 기준으로 만든다
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ids.add(newEvent("원래 이름 " + i, today.plusDays(1 + i)).getId());
        }

        // 자식이 부를 Fake KOPIS (목록은 두 공연을 모두 주고, 행사 날짜를 포함하는 것만 매칭된다)
        HttpServer fakeKopis = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeKopis.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.equals("/pblprfr") ? listXml(today, 0, 2)
                    : detailXml(today, path.endsWith(mt20id(0)) ? 0 : 1, null);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fakeKopis.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-kopis");
            t.setDaemon(true);
            return t;
        }));
        fakeKopis.start();

        File childLog = ProjectPaths.projectRoot().resolve("build/jan04-child.log").toFile();
        Process child = new ProcessBuilder(javaBinary(), "-Xmx384m", "-Daws.accessKeyId=test", "-Daws.secretAccessKey=test",
                "-jar", jar.toString(),
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--server.port=0",
                "--stagelog.jwt.secret=stagelog-test-jwt-secret-0123456789abcdef",
                "--stagelog.kopis.service-key=jan04-smoke",
                "--stagelog.kopis.artist-keyword=스타더스트 클럽",
                "--stagelog.kopis.base-uri=http://127.0.0.1:" + fakeKopis.getAddress().getPort(),
                "--stagelog.sync.cron.kopis=* * * * * *",
                "--stagelog.sync.cron.naver=-",
                "--stagelog.sync.cron.ticketmaster=-",
                "--stagelog.sync.cron.setlistfm=-",
                "--stagelog.push.dispatch-cron=-",
                "--stagelog.push.resync-cron=-",
                // SQS 에 닿지 않는 endpoint: Relay 가 발행에 실패해 Outbox 가 PENDING 으로 남는다
                "--stagelog.aws.endpoint=http://127.0.0.1:1",
                "--jan.pipeline.async-apply.enabled=true",
                "--jan.queue.relay.enabled=true",
                "--jan.queue.auto-create=false",
                "--jan.queue.consumer.threads=0")
                .redirectErrorStream(true).redirectOutput(childLog).start();
        try {
            // 1. 자식이 apply Outbox 2건을 남기고 Relay 가 한 번 이상 발행에 실패할 때까지 (기동 포함 최대 180초).
            //    Relay 는 첫 발행 실패에서 그 주기를 멈추므로 attempts 는 가장 오래된 행에만 쌓인다
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            int pending = 0;
            while (System.nanoTime() < deadline && child.isAlive()) {
                Map<String, Object> row = jdbc.queryForMap("SELECT COUNT(*) AS n, COALESCE(MAX(attempts), 0) AS attempts FROM outbox_events"
                        + " WHERE status = 'PENDING' AND queue_name = 'jan-source-apply'");
                pending = ((Number) row.get("n")).intValue();
                if (pending >= ids.size() && ((Number) row.get("attempts")).intValue() > 0) {
                    break;
                }
                sleep(200);
            }
            Assumptions.assumeTrue(pending >= ids.size(), "자식 프로세스가 Outbox 를 남기지 못했다. 로그: " + childLog);
            String namesBefore = names(ids);

            // 2. kill -9 (Process.destroyForcibly 는 Unix 에서 SIGKILL)
            child.destroyForcibly().waitFor(30, TimeUnit.SECONDS);

            // 3. 재기동: 테스트 JVM 의 Relay 가 남은 행을 발행하고 Consumer 가 처리
            relayOnce();
            int missing = awaitRenamed(ids, 30);
            Integer left = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status <> 'PUBLISHED'", Integer.class);
            log.info("[JAN-04 측정 S4 smoke] kill 전 PENDING(발행 실패 후)={}, kill 전 이름={}, 재발행 후 미발행 행={}, 유실={}/{}, 이름={}",
                    pending, namesBefore, left, missing, ids.size(), names(ids));
        } finally {
            child.destroyForcibly();
            fakeKopis.stop(0);
        }
    }

    // 행사 1개 + 출처 4개(NAVER·KOPIS·TICKETMASTER·SETLISTFM). 시작 시각이 서로 달라 접으면 PENDING 제안이 생긴다
    private long seedSources() {
        Long id = transactions.execute(status -> {
            Event event = Event.create("원래 이름", SupportedLocale.KO, EventType.CONCERT, DATE);
            event.applySource(EventLockableField.STARTS_AT, DATE.atTime(18, 0));
            event = eventRepository.save(event);
            Instant now = Instant.now();
            Map<Provider, SourceSnapshot> snapshots = Map.of(
                    Provider.NAVER, new SourceSnapshot("NV-1", DATE.toString(), "스타더스트 클럽 단독 공연", null, null, null, null,
                            DATE.atTime(19, 0), DATE, null),
                    Provider.KOPIS, kopis("KOPIS 설명"),
                    Provider.TICKETMASTER, new SourceSnapshot("TM-1", DATE.toString(), "STARDUST CLUB LIVE", null,
                            "https://tm.example/1", "https://tm.example/p.jpg", null, DATE.atTime(19, 30), DATE, null),
                    Provider.SETLISTFM, new SourceSnapshot("SF-1", DATE.toString(), "Stardust Club", null, null, null, null, null,
                            DATE, null));
            for (Map.Entry<Provider, SourceSnapshot> e : snapshots.entrySet()) {
                sourceRepository.save(new EventSource(event, e.getKey(), e.getValue().externalId(), e.getValue().occurrenceKey(),
                        codec.write(e.getValue()), now));
            }
            return event.getId();
        });
        return id == null ? -1 : id;
    }

    private static SourceSnapshot kopis(String description) {
        return new SourceSnapshot("PF970001", DATE.toString(), "스타더스트 클럽 [서울]", description, "https://tickets.example/kopis",
                "https://poster.example/kopis.jpg", null, LocalDateTime.of(DATE, java.time.LocalTime.of(20, 0)), DATE, 150);
    }

    private String applyMessage(Provider provider, SourceSnapshot snapshot, long eventId) {
        return "{\"messageId\":\"" + UUID.randomUUID() + "\",\"provider\":\"" + provider + "\",\"externalId\":\"" + snapshot.externalId()
                + "\",\"occurrenceKey\":\"" + snapshot.occurrenceKey() + "\",\"snapshotHash\":\"" + sha256(codec.write(snapshot))
                + "\",\"eventId\":" + eventId + ",\"createdAt\":\"" + Instant.now() + "\"}";
    }

    private Map<String, Object> fingerprint(long eventId) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(jdbc.queryForMap(
                "SELECT id, name, description, ticket_url, poster_key, venue_id, event_date, starts_at_local, running_minutes, status"
                        + " FROM events WHERE id = ?", eventId));
        out.put("pending", jdbc.queryForObject("SELECT COUNT(*) FROM event_source_changes WHERE event_id = ? AND status = 'PENDING'",
                Integer.class, eventId));
        out.put("changes", jdbc.queryForList("SELECT field, status, proposed_value FROM event_source_changes WHERE event_id = ?"
                + " ORDER BY field, proposed_value, status", eventId).toString());
        return out;
    }

    private boolean awaitDrained(String queueUrl, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        int emptyInARow = 0;
        while (System.nanoTime() < deadline) {
            Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                    .attributes();
            boolean empty = "0".equals(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    && "0".equals(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
            emptyInARow = empty ? emptyInARow + 1 : 0;
            if (emptyInARow >= 3) {
                return true;
            }
            sleep(200);
        }
        return false;
    }

    // 병합이 반영되지 않은(이름이 "원래 이름"으로 남은) 행사 수
    private int awaitRenamed(List<Long> ids, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        int missing = ids.size();
        while (System.nanoTime() < deadline) {
            missing = 0;
            for (Long id : ids) {
                String name = jdbc.queryForObject("SELECT name FROM events WHERE id = ?", String.class, id);
                missing += name != null && name.startsWith("원래 이름") ? 1 : 0;
            }
            if (missing == 0) {
                break;
            }
            relayOnce();
            sleep(300);
        }
        return missing;
    }

    private String names(List<Long> ids) {
        return ids.stream().map(id -> jdbc.queryForObject("SELECT name FROM events WHERE id = ?", String.class, id)).toList().toString();
    }

    // count 개 공연을 행사 날짜 TODAY+1.. 에 매칭되게 고정한다. posterRun >= 0 이면 Toxiproxy 를 거치는 포스터 URL 을 준다
    private void stubKopis(int count, int posterRun) {
        for (int i = 0; i < count; i++) {
            String day = YYYYMMDD.format(TODAY.plusDays(1 + i));
            providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith(KOPIS + "/pblprfr?" + KEY + "&stdate=" + day + "&eddate=" + day)))
                    .andRespond(withSuccess(listXml(i, i + 1), MediaType.APPLICATION_XML));
            providers.expect(ExpectedCount.manyTimes(), requestTo(KOPIS + "/pblprfr/" + mt20id(i) + "?" + KEY))
                    .andRespond(withSuccess(detailXml(i, posterRun < 0 ? null : POSTER_BASE + "/poster-" + posterRun + "-" + i + ".png"),
                            MediaType.APPLICATION_XML));
        }
    }

    private static String listXml(int from, int to) {
        return listXml(TODAY, from, to);
    }

    // base+1+i 날짜의 공연 i 들
    private static String listXml(LocalDate base, int from, int to) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs>");
        for (int i = from; i < to; i++) {
            String d = DOTTED.format(base.plusDays(1 + i));
            xml.append("<db><mt20id>").append(mt20id(i)).append("</mt20id><prfnm>스타더스트 클럽 공연 ").append(i)
                    .append("</prfnm><prfpdfrom>").append(d).append("</prfpdfrom><prfpdto>").append(d).append("</prfpdto></db>");
        }
        return xml.append("</dbs>").toString();
    }

    private static String detailXml(int i, String posterUrl) {
        return detailXml(TODAY, i, posterUrl);
    }

    private static String detailXml(LocalDate base, int i, String posterUrl) {
        String d = DOTTED.format(base.plusDays(1 + i));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs><db><mt20id>" + mt20id(i) + "</mt20id><prfnm>스타더스트 클럽 공연 " + i
                + "</prfnm><prfpdfrom>" + d + "</prfpdfrom><prfpdto>" + d + "</prfpdto>"
                + (posterUrl == null ? "" : "<poster>" + posterUrl + "</poster>") + "</db></dbs>";
    }

    private static String mt20id(int i) {
        return "PF96" + String.format("%04d", i);
    }

    private Event newEvent(String name, LocalDate date) {
        return eventRepository.save(Event.create(name, SupportedLocale.KO, EventType.CONCERT, date));
    }

    private boolean present(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // 수정 후 코드의 OutboxRelay.relayOnce(). 없으면(수정 전) 아무것도 하지 않는다
    private void relayOnce() {
        if (!present(RELAY)) {
            return;
        }
        try {
            Object relay = context.getBean(Class.forName(RELAY));
            relay.getClass().getMethod("relayOnce").invoke(relay);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] png() {
        try {
            BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
            image.setRGB(3, 3, 0xFF8800);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer posterServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, PNG.length);
                exchange.getResponseBody().write(PNG);
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "poster-server");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path bootJar() throws IOException {
        Path libs = ProjectPaths.projectRoot().resolve("build/libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (Stream<Path> files = Files.list(libs)) {
            return files.filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-plain.jar")).findFirst().orElse(null);
        }
    }

    private static String javaBinary() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
