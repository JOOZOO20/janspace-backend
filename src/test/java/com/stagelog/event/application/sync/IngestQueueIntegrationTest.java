package com.stagelog.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.SourceSnapshotCodec;
import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.Provider;
import com.stagelog.ops.application.OutboxRelay;
import com.stagelog.ops.application.SyncRunQuery;
import com.stagelog.support.LocalStackIntegrationTest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import com.stagelog.support.WithAdmin;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * JAN-04 수용 기준 (LocalStack SQS). Relay 는 테스트가 pump 로 직접 돌려 "발행 전 종료"를 만들 수 있게 하고, Consumer 는 앱의 polling
 * thread 가 실제 SQS 에서 받는다. 재전달이 빨리 오도록 Visibility Timeout 3초, backoff 1초로 줄인다.
 */
@TestPropertySource(properties = {
        "jan.pipeline.async-apply.enabled=true",
        "jan.queue.relay.enabled=false",
        "jan.queue.visibility-seconds.jan-source-apply=3",
        "jan.queue.visibility-seconds.jan-poster-ingest=3",
        "jan.queue.backoff-seconds=1,1,1,1"})
class IngestQueueIntegrationTest extends LocalStackIntegrationTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    static final String KOPIS = "https://www.kopis.or.kr/openApi/restful";
    static final String KEY = "service=test-kopis-key";
    static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    static final List<String> TABLES = List.of("event_proposals", "event_source_changes", "event_discoveries",
            "event_match_candidates", "event_sources", "event_translations", "events", "venue_sources",
            "venue_translations", "venues", "sync_runs", "sync_item_failures", "outbox_events", "queue_message_failures");
    static final List<String> QUEUES = List.of("jan-source-apply", "jan-source-apply-dlq", "jan-poster-ingest", "jan-poster-ingest-dlq");

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
    SyncRunQuery syncRunQuery;
    @Autowired
    OutboxRelay relay;
    @Autowired
    MockRestServiceServer providers;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    WebApplicationContext context;
    @MockitoSpyBean
    SourceApplyHandler applyHandler;

    // 행사 ID -> seedSources 순번
    final Map<Long, Integer> seeds = new java.util.HashMap<>();
    int seq;

    final SqsClient sqs = SqsClient.builder()
            .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
            .build();

    @BeforeEach
    void clean() {
        QUEUES.forEach(q -> sqs.purgeQueue(b -> b.queueUrl(url(q))));
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("UPDATE job_leases SET owner_id = NULL, expires_at = NULL");
        providers.reset();
    }

    @Test
    @DisplayName("같은 apply 메시지 3회 중복 전달 = 1회 처리 (행사 데이터, PENDING 제안 수, 변경 이력)")
    void duplicated_apply_message_equals_single() {
        long once = seedSources();
        send("jan-source-apply", applyMessage(Provider.NAVER, once));
        assertThat(drained("jan-source-apply")).isTrue();

        long thrice = seedSources();
        String body = applyMessage(Provider.NAVER, thrice);
        for (int i = 0; i < 3; i++) {
            send("jan-source-apply", body);
        }
        assertThat(drained("jan-source-apply")).isTrue();

        Map<String, Object> single = fingerprint(once);
        assertThat(single.get("pending")).as("시작 시각 충돌로 PENDING 제안이 생기는 시나리오").isEqualTo(1);
        assertThat(fingerprint(thrice)).isEqualTo(single);
    }

    @Test
    @DisplayName("메시지 순서를 섞고 1~3회 복제해 전달하는 100회에서 최종 병합 결과가 모두 같다")
    void shuffled_messages_converge_100_times() {
        long reference = seedSources();
        applySources.refold(reference);
        Map<String, Object> expected = fingerprint(reference);
        Random random = new Random(20261105L);
        List<Integer> mismatches = new ArrayList<>();
        for (int round = 0; round < 100; round++) {
            long eventId = seedSources();
            List<String> bodies = new ArrayList<>();
            for (Provider provider : List.of(Provider.NAVER, Provider.KOPIS, Provider.TICKETMASTER, Provider.SETLISTFM)) {
                bodies.add(applyMessage(provider, eventId));
            }
            List<String> deliveries = new ArrayList<>();
            for (String body : bodies) {
                for (int c = 0; c <= random.nextInt(3); c++) {
                    deliveries.add(body);
                }
            }
            Collections.shuffle(deliveries, random);
            deliveries.forEach(applyHandler::handle);
            if (!fingerprint(eventId).equals(expected)) {
                mismatches.add(round);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    @DisplayName("KOPIS 응답 5건 중 1건 파싱 오류 -> 나머지 4건 저장, 실행 PARTIAL, 실패 1건 기록, 커서 미전진")
    void kopis_isolates_broken_item() {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(newEvent("원래 이름 " + i, TODAY.plusDays(1 + i)).getId());
        }
        stubKopis(5, 2, null);
        jdbc.update("INSERT INTO sync_runs (source, started_at, finished_at, status, summary, created_at, updated_at)"
                + " VALUES ('KOPIS', NOW(6) - INTERVAL 1 DAY, NOW(6) - INTERVAL 1 DAY, 'OK', 'seed', NOW(6), NOW(6))");
        Optional<Instant> cursor = syncRunQuery.lastSucceededAt("KOPIS");

        SyncResult result = enrichment.enrich(TODAY);

        assertThat(result.partial()).isTrue();
        for (int i = 0; i < 5; i++) {
            Integer linked = jdbc.queryForObject("SELECT COUNT(*) FROM event_sources WHERE event_id = ? AND provider = 'KOPIS'",
                    Integer.class, ids.get(i));
            assertThat(linked).as("event %d", i).isEqualTo(i == 2 ? 0 : 1);
        }
        Map<String, Object> run = jdbc.queryForMap("SELECT id, status FROM sync_runs WHERE summary <> 'seed'");
        assertThat(run.get("status")).isEqualTo("PARTIAL");
        assertThat(jdbc.queryForList("SELECT external_id FROM sync_item_failures WHERE sync_run_id = ?", String.class, run.get("id")))
                .containsExactly("event-" + ids.get(2));
        assertThat(syncRunQuery.lastSucceededAt("KOPIS")).isEqualTo(cursor);
    }

    @Test
    @DisplayName("포스터 일시 오류(5xx)는 재전달 후 성공, 영구 오류(404)는 재시도 없이 실패 기록 후 원본 URL 유지")
    void poster_transient_then_success_and_permanent_404() {
        Long ok = newEvent("원래 이름 0", TODAY.plusDays(1)).getId();
        Long gone = newEvent("원래 이름 1", TODAY.plusDays(2)).getId();
        String okUrl = "http://poster.test/ok.png";
        String goneUrl = "http://poster.test/gone.png";
        stubKopis(2, -1, List.of(okUrl, goneUrl));
        providers.expect(ExpectedCount.once(), requestTo(okUrl)).andRespond(withServerError());
        providers.expect(ExpectedCount.once(), requestTo(okUrl)).andRespond(withSuccess(png(), MediaType.IMAGE_PNG));
        providers.expect(ExpectedCount.manyTimes(), requestTo(goneUrl)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        enrichment.enrich(TODAY);

        assertThat(await(() -> posterKey(ok).startsWith("events/" + ok + "/poster/") && failure(posterMessageId(gone)) != null))
                .as("5xx 뒤 재전달로 S3 key, 404 는 실패 기록").isTrue();
        Map<String, Object> transientFailure = failure(posterMessageId(ok));
        assertThat(transientFailure.get("attempts")).isEqualTo(1);
        assertThat(transientFailure.get("permanent")).isEqualTo(false);
        // 재시도가 있었다면 backoff(1초) 뒤 attempts 가 늘어난다
        sleep(3000);
        Map<String, Object> permanent = failure(posterMessageId(gone));
        assertThat(permanent.get("attempts")).isEqualTo(1);
        assertThat(permanent.get("permanent")).isEqualTo(true);
        assertThat(posterKey(gone)).isEqualTo(goneUrl);
        assertThat(drained("jan-poster-ingest")).isTrue();
    }

    @Test
    @DisplayName("Consumer 처리 중 강제 종료 -> Visibility 만료 후 재처리, 결과는 정상 1회 처리와 같다")
    void consumer_dies_mid_processing_then_message_is_redelivered() {
        long reference = seedSources();
        applySources.refold(reference);
        long eventId = seedSources();
        // 첫 처리는 삭제·연장·실패 기록 없이 스레드를 벗어난다 (프로세스가 죽은 것과 같은 SQS 상태)
        doThrow(new Error("처리 중 종료 재현")).doCallRealMethod().when(applyHandler).handle(anyString());

        send("jan-source-apply", applyMessage(Provider.KOPIS, eventId));

        assertThat(await(() -> fingerprint(eventId).equals(fingerprint(reference)))).isTrue();
        verify(applyHandler, atLeast(2)).handle(anyString());
        assertThat(drained("jan-source-apply")).isTrue();
    }

    @Test
    @DisplayName("출처 저장 커밋 후 Relay 발행 전에 서버 종료 -> 재기동 후 발행, 유실 0건")
    void outbox_survives_crash_before_relay() {
        List<Long> ids = List.of(newEvent("원래 이름 0", TODAY.plusDays(1)).getId(), newEvent("원래 이름 1", TODAY.plusDays(2)).getId());
        stubKopis(2, -1, null);

        enrichment.enrich(TODAY);

        // 수집은 출처와 Outbox 만 커밋했다. Relay 가 돌지 않았으므로 아무것도 발행·병합되지 않았다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING' AND queue_name = 'jan-source-apply'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_sources WHERE provider = 'KOPIS'", Integer.class)).isEqualTo(2);
        sleep(1500);
        assertThat(names(ids)).allMatch(n -> n.startsWith("원래 이름"));

        // 재기동: Relay 실행
        assertThat(relay.relayOnce()).isEqualTo(2);
        assertThat(await(() -> names(ids).stream().noneMatch(n -> n.startsWith("원래 이름")))).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE status <> 'PUBLISHED'", Integer.class)).isZero();
    }

    @Test
    @WithAdmin
    @DisplayName("5회 실패한 메시지는 DLQ 로 가고, 관리 API 로 조회·redrive 하면 정상 처리된다. redrive 는 감사 로그 DLQ_REDRIVE 를 남긴다")
    void dead_letter_redrive() throws Exception {
        // redrive 는 쓰기라 동의 게이트가 관리자 users 행을 본다
        jdbc.update("DELETE FROM users WHERE id = ?", WithAdmin.ID);
        jdbc.update("INSERT INTO users (id, nickname, locale, is_admin, consented_at, terms_version, created_at, updated_at) "
                + "VALUES (?, 'admin', 'ko', TRUE, UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", WithAdmin.ID);
        long reference = seedSources();
        applySources.refold(reference);
        long eventId = seedSources();
        IllegalStateException down = new IllegalStateException("일시 오류 재현");
        doThrow(down).doThrow(down).doThrow(down).doThrow(down).doThrow(down).doCallRealMethod()
                .when(applyHandler).handle(anyString());
        String body = applyMessage(Provider.TICKETMASTER, eventId);
        String messageId = body.substring(body.indexOf(":\"") + 2, body.indexOf("\","));

        send("jan-source-apply", body);
        assertThat(await(() -> visible("jan-source-apply-dlq") == 1)).as("5회 실패 후 DLQ").isTrue();
        assertThat(fingerprint(eventId)).isNotEqualTo(fingerprint(reference));

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/v1/admin/queues/jan-source-apply/dead-letters").param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].messageId").value(messageId))
                .andExpect(jsonPath("$.data[0].receiveCount").value(5))
                .andExpect(jsonPath("$.data[0].lastError").value("IllegalStateException: 일시 오류 재현"));
        sleep(6000); // 목록 조회가 잠깐 숨긴 메시지가 다시 보일 때까지
        mvc.perform(post("/api/v1/admin/queues/jan-source-apply/dead-letters/redrive").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[\"" + messageId + "\",\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redriven[0]").value(messageId))
                .andExpect(jsonPath("$.data.notFound.length()").value(1));

        assertThat(await(() -> fingerprint(eventId).equals(fingerprint(reference)))).as("redrive 후 정상 처리").isTrue();
        assertThat(visible("jan-source-apply-dlq")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_logs WHERE action = 'DLQ_REDRIVE' AND target_id = 'jan-source-apply' "
                + "AND JSON_CONTAINS(after_json, JSON_QUOTE(?), '$.redriven') AND actor_id = ?", Integer.class, messageId,
                String.valueOf(WithAdmin.ID))).isEqualTo(1);
        mvc.perform(get("/api/v1/admin/queues/unknown/dead-letters")).andExpect(status().isNotFound());
    }

    // 행사 1개 + 출처 4개. 시작 시각이 서로 달라 접으면 PENDING 제안이 생긴다. 외부 ID 는 (provider, external_id, occurrence_key) 유일이라 행사마다 다르다
    private long seedSources() {
        Long id = transactions.execute(status -> {
            Event event = Event.create("원래 이름", SupportedLocale.KO, EventType.CONCERT, DATE);
            event.applySource(EventLockableField.STARTS_AT, DATE.atTime(18, 0));
            event = eventRepository.save(event);
            Instant now = Instant.now();
            for (Map.Entry<Provider, SourceSnapshot> e : snapshots(seq).entrySet()) {
                sourceRepository.save(new EventSource(event, e.getKey(), e.getValue().externalId(), e.getValue().occurrenceKey(),
                        codec.write(e.getValue()), now));
            }
            return event.getId();
        });
        seeds.put(id, seq++);
        return id == null ? -1 : id;
    }

    private static Map<Provider, SourceSnapshot> snapshots(int seq) {
        Map<Provider, SourceSnapshot> out = new LinkedHashMap<>();
        out.put(Provider.NAVER, new SourceSnapshot("NV-" + seq, DATE.toString(), "스타더스트 클럽 단독 공연", null, null, null, null,
                DATE.atTime(19, 0), DATE, null));
        out.put(Provider.KOPIS, new SourceSnapshot("PF97" + seq, DATE.toString(), "스타더스트 클럽 [서울]", "KOPIS 설명",
                "https://tickets.example/kopis", "https://poster.example/kopis.jpg", null, LocalDateTime.of(DATE, LocalTime.of(20, 0)),
                DATE, 150));
        out.put(Provider.TICKETMASTER, new SourceSnapshot("TM-" + seq, DATE.toString(), "STARDUST CLUB LIVE", null, "https://tm.example/1",
                "https://tm.example/p.jpg", null, DATE.atTime(19, 30), DATE, null));
        out.put(Provider.SETLISTFM, new SourceSnapshot("SF-" + seq, DATE.toString(), "Stardust Club", null, null, null, null, null, DATE, null));
        return out;
    }

    private String applyMessage(Provider provider, long eventId) {
        SourceSnapshot snapshot = snapshots(seeds.get(eventId)).get(provider);
        return "{\"messageId\":\"" + UUID.randomUUID() + "\",\"provider\":\"" + provider + "\",\"externalId\":\"" + snapshot.externalId()
                + "\",\"occurrenceKey\":\"" + snapshot.occurrenceKey() + "\",\"snapshotHash\":\"" + sha256(codec.write(snapshot))
                + "\",\"eventId\":" + eventId + ",\"createdAt\":\"" + Instant.now() + "\"}";
    }

    private Map<String, Object> fingerprint(long eventId) {
        Map<String, Object> out = new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT name, description, ticket_url, poster_key, venue_id, event_date, starts_at_local, running_minutes, status"
                        + " FROM events WHERE id = ?", eventId));
        out.put("pending", jdbc.queryForObject("SELECT COUNT(*) FROM event_source_changes WHERE event_id = ? AND status = 'PENDING'",
                Integer.class, eventId));
        out.put("changes", jdbc.queryForList("SELECT field, status, proposed_value FROM event_source_changes WHERE event_id = ?"
                + " ORDER BY field, proposed_value, status", eventId).toString());
        return out;
    }

    // count 개 공연을 TODAY+1.. 행사에 매칭되게 고정. broken 번째 상세는 잘린 XML, posters 가 있으면 i 번째 포스터 URL
    private void stubKopis(int count, int broken, List<String> posters) {
        for (int i = 0; i < count; i++) {
            String id = "PF98" + String.format("%04d", i);
            LocalDate date = TODAY.plusDays(1 + i);
            String d = DOTTED.format(date);
            String day = YYYYMMDD.format(date);
            providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith(KOPIS + "/pblprfr?" + KEY + "&stdate=" + day + "&eddate=" + day)))
                    .andRespond(withSuccess("<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs><db><mt20id>" + id + "</mt20id><prfnm>스타더스트 클럽 공연 "
                            + i + "</prfnm><prfpdfrom>" + d + "</prfpdfrom><prfpdto>" + d + "</prfpdto></db></dbs>", MediaType.APPLICATION_XML));
            String detail = i == broken ? "<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs><db><mt20id>" + id + "</mt20id><prfnm>잘린"
                    : "<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs><db><mt20id>" + id + "</mt20id><prfnm>스타더스트 클럽 공연 " + i
                    + "</prfnm><prfpdfrom>" + d + "</prfpdfrom><prfpdto>" + d + "</prfpdto>"
                    + (posters == null ? "" : "<poster>" + posters.get(i) + "</poster>") + "</db></dbs>";
            providers.expect(ExpectedCount.manyTimes(), requestTo(KOPIS + "/pblprfr/" + id + "?" + KEY))
                    .andRespond(withSuccess(detail, MediaType.APPLICATION_XML));
        }
    }

    private Event newEvent(String name, LocalDate date) {
        return eventRepository.save(Event.create(name, SupportedLocale.KO, EventType.CONCERT, date));
    }

    private String posterKey(Long eventId) {
        String key = jdbc.queryForObject("SELECT poster_key FROM events WHERE id = ?", String.class, eventId);
        return key == null ? "" : key;
    }

    private String posterMessageId(Long eventId) {
        return jdbc.queryForList("SELECT message_id FROM outbox_events WHERE queue_name = 'jan-poster-ingest'", String.class).stream()
                .filter(id -> jdbc.queryForObject("SELECT payload FROM outbox_events WHERE message_id = ?", String.class, id)
                        .contains("\"eventId\": " + eventId + ","))
                .findFirst().orElseThrow();
    }

    private Map<String, Object> failure(String messageId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT attempts, permanent, last_error FROM queue_message_failures WHERE message_id = ?", messageId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        Object permanent = row.get("permanent");
        row.put("permanent", permanent instanceof Boolean flag ? flag : ((Number) permanent).intValue() == 1);
        return row;
    }

    private List<String> names(List<Long> ids) {
        return ids.stream().map(id -> jdbc.queryForObject("SELECT name FROM events WHERE id = ?", String.class, id)).toList();
    }

    private void send(String queue, String body) {
        sqs.sendMessage(b -> b.queueUrl(url(queue)).messageBody(body));
    }

    private String url(String queue) {
        return sqs.getQueueUrl(b -> b.queueName(queue)).queueUrl();
    }

    private int visible(String queue) {
        return Integer.parseInt(sqs.getQueueAttributes(b -> b.queueUrl(url(queue))
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)).attributes()
                .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
    }

    private boolean drained(String queue) {
        return await(() -> {
            Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes(b -> b.queueUrl(url(queue)).attributeNames(
                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE))
                    .attributes();
            return "0".equals(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    && "0".equals(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
        });
    }

    // 30초 안에 조건이 참이 되면 true. 조건이 처음 참이 되고 0.5초 뒤에도 참인지 한 번 더 본다. 기다리는 동안 Relay 를 돌린다
    private boolean await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            relay.relayOnce();
            if (condition.getAsBoolean()) {
                sleep(500);
                if (condition.getAsBoolean()) {
                    return true;
                }
            }
            sleep(200);
        }
        return false;
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
            BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
