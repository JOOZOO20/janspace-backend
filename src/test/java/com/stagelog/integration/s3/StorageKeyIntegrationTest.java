package com.stagelog.integration.s3;

import static com.stagelog.integration.s3.Jan02StorageKeyMeasurementTest.png;
import static com.stagelog.integration.s3.Jan02StorageKeyMeasurementTest.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.catalog.application.CatalogCommand;
import com.stagelog.catalog.domain.Album;
import com.stagelog.catalog.domain.AlbumRepository;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.EventCommand;
import com.stagelog.event.application.PosterKeyBackfill;
import com.stagelog.event.application.SourceSnapshotCodec;
import com.stagelog.event.application.sync.KopisEnrichment;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.Provider;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.ops.application.StorageCleanup;
import com.stagelog.ops.domain.SyncRunRepository;
import com.stagelog.support.LocalStackIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * JAN-02 수용 기준 (LocalStack S3 실객체): 교체 즉시 새 URL·내용 일치, 같은 내용 PUT 0회, Cache-Control immutable,
 * 이전 key 7일 보존 후 삭제, DB(poster_key·cover_key·출처 스냅샷)가 참조하는 key 는 지우지 않음. 고정 key 백필과 플래그 off 도 본다.
 */
class StorageKeyIntegrationTest extends LocalStackIntegrationTest {

    private static final AuditActor ADMIN_ACTOR = AuditActor.admin(1L);

    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    static final String KOPIS = "https://www.kopis.or.kr/openApi/restful";
    static final String KOPIS_KEY = "service=test-kopis-key";
    static final String KOPIS_POSTER = "http://www.kopis.or.kr/upload/pfmPoster/PF_PF900001_260901_101021.gif";
    static final String IMMUTABLE = "public, max-age=31536000, immutable";

    final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;
    @Autowired
    EventCommand eventCommand;
    @Autowired
    CatalogCommand catalogCommand;
    @Autowired
    KopisEnrichment kopisEnrichment;
    @Autowired
    ApplySourcesUseCase applySources;
    @Autowired
    PosterKeyBackfill posterKeyBackfill;
    @Autowired
    StorageCleanup storageCleanup;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    EventSourceRepository sourceRepository;
    @Autowired
    SourceSnapshotCodec codec;
    @Autowired
    AlbumRepository albumRepository;
    @Autowired
    SyncRunRepository syncRuns;
    @Autowired
    MockRestServiceServer providers;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        Jan02StorageKeyMeasurementTest.EVENT_TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        jdbc.execute("TRUNCATE TABLE s3_delete_schedule");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        syncRuns.deleteAll();
        providers.reset();
    }

    @Test
    @DisplayName("교체 직후 API 가 새 URL 을 주고, 그 URL 의 파일 sha256 이 새 이미지와 같으며 Cache-Control immutable 이다")
    void replacement_returns_new_url_with_new_content() throws Exception {
        Long eventId = newEvent("교체 행사", TODAY);
        String first = eventCommand.uploadPoster(eventId, png("a"), "image/png", ADMIN_ACTOR);
        byte[] replacement = png("b");

        String second = eventCommand.uploadPoster(eventId, replacement, "image/png", ADMIN_ACTOR);

        assertThat(second).isNotEqualTo(first).startsWith(bucketUrl() + "/events/" + eventId + "/poster/").endsWith(".png");
        HttpResponse<byte[]> object = get(second);
        assertThat(sha256(object.body())).isEqualTo(sha256(replacement));
        assertThat(object.headers().firstValue("Cache-Control")).hasValue(IMMUTABLE);
        String api = new String(get("http://localhost:" + port + "/api/v1/events/" + eventId).body(), StandardCharsets.UTF_8);
        assertThat(api).contains("\"posterUrl\":\"" + second + "\"");

        Long albumId = newAlbum();
        String cover1 = catalogCommand.uploadCover(albumId, png("cover-a"));
        byte[] cover = png("cover-b");
        String cover2 = catalogCommand.uploadCover(albumId, cover);
        assertThat(cover2).isNotEqualTo(cover1).startsWith(bucketUrl() + "/albums/" + albumId + "/");
        HttpResponse<byte[]> coverObject = get(cover2);
        assertThat(sha256(coverObject.body())).isEqualTo(sha256(cover));
        assertThat(coverObject.headers().firstValue("Cache-Control")).hasValue(IMMUTABLE);
    }

    @Test
    @DisplayName("같은 내용을 다시 올리면 S3 PUT 0회, URL 그대로, 삭제 예약 없음")
    void same_content_is_not_uploaded_again() {
        Long eventId = newEvent("재업로드 행사", TODAY);
        String url = eventCommand.uploadPoster(eventId, png("same"), "image/png", ADMIN_ACTOR);
        Long albumId = newAlbum();
        String coverUrl = catalogCommand.uploadCover(albumId, png("same-cover"));
        Mockito.clearInvocations(s3Client);

        assertThat(eventCommand.uploadPoster(eventId, png("same"), "image/png", ADMIN_ACTOR)).isEqualTo(url);
        assertThat(catalogCommand.uploadCover(albumId, png("same-cover"))).isEqualTo(coverUrl);

        assertThat(putCount()).isZero();
        assertThat(scheduleCount()).isZero();
    }

    @Test
    @DisplayName("KOPIS 가 같은 포스터를 10회 다시 보내면 PUT 0회, 바뀐 포스터는 새 key 로 반영되고 이전 key 는 삭제 예약")
    void kopis_resend_is_not_uploaded_again() {
        Long eventId = newEvent("스타더스트 클럽 콘서트 KSPO DOME", LocalDate.of(2026, 10, 23));
        expectKopis("GIF89a-v1".getBytes(StandardCharsets.UTF_8));
        kopisEnrichment.enrich(TODAY);
        String first = posterKey(eventId);
        assertThat(first).startsWith("events/" + eventId + "/poster/").endsWith(".gif");
        assertThat(kopisRaw(eventId).posterUrl()).isEqualTo(first);
        Mockito.clearInvocations(s3Client);

        for (int i = 0; i < 10; i++) {
            kopisEnrichment.enrich(TODAY);
        }
        assertThat(putCount()).isZero();
        assertThat(posterKey(eventId)).isEqualTo(first);

        providers.reset();
        expectKopis("GIF89a-v2".getBytes(StandardCharsets.UTF_8));
        kopisEnrichment.enrich(TODAY);
        String changed = posterKey(eventId);
        assertThat(changed).isNotEqualTo(first).startsWith("events/" + eventId + "/poster/");
        assertThat(reason(first)).isEqualTo("REPLACED");
    }

    @Test
    @DisplayName("교체된 이전 key 는 7일(DB 시계) 동안 접근 가능하고 기한이 지나면 배치가 지운다")
    void replaced_key_lives_seven_days_then_deleted() throws Exception {
        Long eventId = newEvent("보존 행사", TODAY);
        String oldUrl = eventCommand.uploadPoster(eventId, png("old"), "image/png", ADMIN_ACTOR);
        String oldKey = posterKey(eventId);
        String newUrl = eventCommand.uploadPoster(eventId, png("new"), "image/png", ADMIN_ACTOR);

        Integer minutesLeft = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(MINUTE, NOW(6), delete_after) FROM s3_delete_schedule WHERE object_key = ?", Integer.class, oldKey);
        assertThat(minutesLeft).isBetween(7 * 24 * 60 - 1, 7 * 24 * 60);
        assertThat(storageCleanup.deleteDue()).isZero();
        assertThat(get(oldUrl).statusCode()).isEqualTo(200);

        // 7일 경과: 기한을 DB 시계 기준 과거로 옮긴다
        jdbc.update("UPDATE s3_delete_schedule SET delete_after = NOW(6) - INTERVAL 1 SECOND WHERE object_key = ?", oldKey);
        assertThat(storageCleanup.deleteDue()).isEqualTo(1);

        assertThat(get(oldUrl).statusCode()).isEqualTo(404);
        assertThat(get(newUrl).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM s3_delete_schedule WHERE object_key = ?",
                Boolean.class, oldKey)).isTrue();
    }

    @Test
    @DisplayName("DB 가 참조 중인 key 는 예약 기한이 지나도 지우지 않는다 (poster_key 로 되돌아온 key, cover_key, 잠금 뒤 출처 스냅샷의 key)")
    void referenced_keys_are_not_deleted() throws Exception {
        // 1. A -> B -> A(같은 내용): A 는 다시 poster_key, B 는 참조 없음
        Long eventId = newEvent("참조 행사", TODAY);
        String urlA = eventCommand.uploadPoster(eventId, png("A"), "image/png", ADMIN_ACTOR);
        String urlB = eventCommand.uploadPoster(eventId, png("B"), "image/png", ADMIN_ACTOR);
        assertThat(eventCommand.uploadPoster(eventId, png("A"), "image/png", ADMIN_ACTOR)).isEqualTo(urlA);

        // 2. 커버: 현재 key 를 억지로 예약해도 cover_key 가 참조하므로 남는다
        Long albumId = newAlbum();
        String coverUrl = catalogCommand.uploadCover(albumId, png("cover"));
        jdbc.update("INSERT INTO s3_delete_schedule (object_key, delete_after, reason, created_at, updated_at) "
                + "VALUES (?, NOW(6), 'REPLACED', NOW(6), NOW(6))", coverUrl.substring(bucketUrl().length() + 1));

        // 3. KOPIS 포스터 K 위에 관리자 포스터를 올리면 K 는 예약되지만 출처 스냅샷(raw)이 계속 참조한다
        Long kopisEventId = newEvent("스타더스트 클럽 콘서트 KSPO DOME", LocalDate.of(2026, 10, 23));
        expectKopis("GIF89a-kopis".getBytes(StandardCharsets.UTF_8));
        kopisEnrichment.enrich(TODAY);
        String kopisKey = posterKey(kopisEventId);
        eventCommand.uploadPoster(kopisEventId, png("admin"), "image/png", ADMIN_ACTOR);
        assertThat(reason(kopisKey)).isEqualTo("REPLACED");

        jdbc.update("UPDATE s3_delete_schedule SET delete_after = NOW(6) - INTERVAL 1 SECOND");
        storageCleanup.deleteDue();

        assertThat(get(urlA).statusCode()).isEqualTo(200);
        assertThat(get(urlB).statusCode()).isEqualTo(404);
        assertThat(get(coverUrl).statusCode()).isEqualTo(200);
        assertThat(get(bucketUrl() + "/" + kopisKey).statusCode()).isEqualTo(200);
        // 잠금 해제 -> 출처 포스터로 되돌아가도 객체가 남아 있다
        eventCommand.unlock(kopisEventId, EventLockableField.POSTER, ADMIN_ACTOR);
        assertThat(posterKey(kopisEventId)).isEqualTo(kopisKey);
    }

    @Test
    @DisplayName("백필: 고정 key 포스터·커버를 내용 hash key 로 복사하고 poster_key·출처 스냅샷·cover_key 를 바꾼 뒤 옛 key 를 MIGRATED 예약, 재접기도 새 key")
    void backfill_moves_fixed_keys() throws Exception {
        byte[] poster = "GIF89a-legacy".getBytes(StandardCharsets.UTF_8);
        byte[] cover = png("legacy-cover");
        Long eventId = newEvent("백필 행사", TODAY);
        String fixedPoster = "events/" + eventId + "/poster.gif";
        Long albumId = newAlbum();
        String fixedCover = "albums/" + albumId + ".png";
        try (S3Client admin = adminS3()) {
            admin.putObject(b -> b.bucket(BUCKET).key(fixedPoster).contentType("image/gif"), RequestBody.fromBytes(poster));
            admin.putObject(b -> b.bucket(BUCKET).key(fixedCover).contentType("image/png"), RequestBody.fromBytes(cover));
        }
        // 수정 전 KOPIS 재저장과 같은 상태: 출처 스냅샷과 poster_key 가 모두 고정 key
        applySources.apply(eventId, Map.of(Provider.KOPIS, new SourceSnapshot("PF1", TODAY.toString(), null, null, null,
                fixedPoster, null, null, TODAY, null)));
        jdbc.update("UPDATE albums SET cover_key = ? WHERE id = ?", fixedCover, albumId);
        assertThat(posterKey(eventId)).isEqualTo(fixedPoster);

        assertThat(posterKeyBackfill.run()).isEqualTo(new PosterKeyBackfill.Result(1, 0));
        assertThat(catalogCommand.backfillVersionedCoverKeys()).isEqualTo(new CatalogCommand.CoverBackfillResult(1, 0));

        String newPoster = posterKey(eventId);
        assertThat(newPoster).startsWith("events/" + eventId + "/poster/").endsWith(".gif");
        assertThat(kopisRaw(eventId).posterUrl()).isEqualTo(newPoster);
        applySources.refold(eventId);
        assertThat(posterKey(eventId)).as("출처 스냅샷도 바꿨으므로 재접기가 옛 key 로 되돌리지 않는다").isEqualTo(newPoster);
        HttpResponse<byte[]> copied = get(bucketUrl() + "/" + newPoster);
        assertThat(sha256(copied.body())).isEqualTo(sha256(poster));
        assertThat(copied.headers().firstValue("Cache-Control")).hasValue(IMMUTABLE);
        String newCover = albumRepository.findById(albumId).orElseThrow().getCoverKey();
        assertThat(newCover).startsWith("albums/" + albumId + "/").endsWith(".png");
        assertThat(sha256(get(bucketUrl() + "/" + newCover).body())).isEqualTo(sha256(cover));
        assertThat(reason(fixedPoster)).isEqualTo("MIGRATED");
        assertThat(reason(fixedCover)).isEqualTo("MIGRATED");

        // 다시 실행하면 남은 고정 key 가 없다
        assertThat(posterKeyBackfill.run()).isEqualTo(new PosterKeyBackfill.Result(0, 0));
        assertThat(catalogCommand.backfillVersionedCoverKeys()).isEqualTo(new CatalogCommand.CoverBackfillResult(0, 0));
    }

    @Test
    @DisplayName("jan.storage.versioned-key.enabled=false 면 고정 key 덮어쓰기(기존 동작), Cache-Control 없음, 삭제 예약 없음")
    void flag_off_keeps_fixed_key() throws Exception {
        Object command = AopTestUtils.getTargetObject(eventCommand);
        Object cleanup = AopTestUtils.getTargetObject(storageCleanup);
        ReflectionTestUtils.setField(command, "versionedKey", false);
        ReflectionTestUtils.setField(cleanup, "versionedKey", false);
        try {
            Long eventId = newEvent("플래그 행사", TODAY);
            String first = eventCommand.uploadPoster(eventId, png("x"), "image/png", ADMIN_ACTOR);
            String second = eventCommand.uploadPoster(eventId, png("y"), "image/png", ADMIN_ACTOR);

            assertThat(second).isEqualTo(first).isEqualTo(bucketUrl() + "/events/" + eventId + "/poster.png");
            assertThat(get(second).headers().firstValue("Cache-Control")).isEmpty();
            assertThat(scheduleCount()).isZero();
        } finally {
            ReflectionTestUtils.setField(command, "versionedKey", true);
            ReflectionTestUtils.setField(cleanup, "versionedKey", true);
        }
    }

    private Long newEvent(String name, LocalDate date) {
        return eventRepository.save(Event.create(name, SupportedLocale.KO, EventType.CONCERT, date)).getId();
    }

    private Long newAlbum() {
        return albumRepository.save(new Album("JAN-02 앨범 " + System.nanoTime(), SupportedLocale.KO.tag())).getId();
    }

    private String posterKey(Long eventId) {
        return eventRepository.findById(eventId).orElseThrow().getPosterKey();
    }

    private SourceSnapshot kopisRaw(Long eventId) {
        return codec.read(sourceRepository.findByEventIdAndProvider(eventId, Provider.KOPIS).orElseThrow().getRaw());
    }

    private String reason(String key) {
        List<String> reasons = jdbc.queryForList("SELECT reason FROM s3_delete_schedule WHERE object_key = ?", String.class, key);
        return reasons.isEmpty() ? null : reasons.get(0);
    }

    private int scheduleCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM s3_delete_schedule", Integer.class);
    }

    private int putCount() {
        return (int) Mockito.mockingDetails(s3Client).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("putObject")).count();
    }

    private void expectKopis(byte[] poster) {
        providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString("/pblprfr?" + KOPIS_KEY)))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/list-one.xml"), MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(KOPIS + "/pblprfr/PF900001?" + KOPIS_KEY))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/detail-PF900001.xml"), MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(KOPIS + "/prfplc/FC900001?" + KOPIS_KEY))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/facility-FC900001.xml"), MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(KOPIS_POSTER)).andRespond(withSuccess(poster, MediaType.IMAGE_GIF));
    }

    private HttpResponse<byte[]> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
