package com.stagelog.integration.s3;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.catalog.application.CatalogCommand;
import com.stagelog.catalog.domain.Album;
import com.stagelog.catalog.domain.AlbumRepository;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.EventCommand;
import com.stagelog.event.application.sync.KopisEnrichment;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.Provider;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.ops.domain.SyncRunRepository;
import com.stagelog.support.LocalStackIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;

/**
 * JAN-02 측정 (docs/measurements/JAN-02.md). 수정 전후 같은 공개 진입점(uploadPoster, uploadCover, KopisEnrichment.enrich)으로
 * 이미지 교체 후 URL 재사용 횟수와 같은 내용 재전송 시 S3 PUT 수를 센다. assert 하지 않고 수치만 출력한다 (판정은 리포트).
 */
@Slf4j
@Tag("measurement")
class Jan02StorageKeyMeasurementTest extends LocalStackIntegrationTest {

    private static final AuditActor ADMIN_ACTOR = AuditActor.admin(1L);

    static final int REPLACEMENTS = 50;
    static final int RESENDS = 10;
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    static final String KOPIS = "https://www.kopis.or.kr/openApi/restful";
    static final String KOPIS_KEY = "service=test-kopis-key";
    static final String KOPIS_POSTER = "http://www.kopis.or.kr/upload/pfmPoster/PF_PF900001_260901_101021.gif";
    static final List<String> EVENT_TABLES = List.of("event_proposals", "event_source_changes", "event_discoveries",
            "event_match_candidates", "event_sources", "event_translations", "events", "venue_sources",
            "venue_translations", "venues");

    final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    EventCommand eventCommand;
    @Autowired
    CatalogCommand catalogCommand;
    @Autowired
    KopisEnrichment kopisEnrichment;
    @Autowired
    EventRepository eventRepository;
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
        EVENT_TABLES.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t));
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        syncRuns.deleteAll();
        providers.reset();
    }

    @Test
    void measure() throws Exception {
        StringBuilder out = new StringBuilder("\n[JAN-02 측정]\n");

        // S1 포스터 교체 50회
        Long eventId = eventRepository.save(Event.create("JAN-02 측정 행사", SupportedLocale.KO, EventType.CONCERT, TODAY)).getId();
        String url = eventCommand.uploadPoster(eventId, png("poster-0"), "image/png", ADMIN_ACTOR);
        int reused = 0;
        int mismatched = 0;
        byte[] last = null;
        for (int i = 1; i <= REPLACEMENTS; i++) {
            last = png("poster-" + i);
            String next = eventCommand.uploadPoster(eventId, last, "image/png", ADMIN_ACTOR);
            reused += next.equals(url) ? 1 : 0;
            mismatched += sha256(get(next)).equals(sha256(last)) ? 0 : 1;
            url = next;
        }
        out.append("S1 포스터 교체: URL 재사용 ").append(reused).append('/').append(REPLACEMENTS)
                .append(", 내용 불일치 ").append(mismatched).append('/').append(REPLACEMENTS).append('\n');

        // S2 앨범커버 교체 50회
        Long albumId = albumRepository.save(new Album("JAN-02 측정 앨범 " + System.nanoTime(), SupportedLocale.KO.tag())).getId();
        String coverUrl = catalogCommand.uploadCover(albumId, png("cover-0"));
        int coverReused = 0;
        int coverMismatched = 0;
        for (int i = 1; i <= REPLACEMENTS; i++) {
            byte[] cover = png("cover-" + i);
            String next = catalogCommand.uploadCover(albumId, cover);
            coverReused += next.equals(coverUrl) ? 1 : 0;
            coverMismatched += sha256(get(next)).equals(sha256(cover)) ? 0 : 1;
            coverUrl = next;
        }
        out.append("S2 앨범커버 교체: URL 재사용 ").append(coverReused).append('/').append(REPLACEMENTS)
                .append(", 내용 불일치 ").append(coverMismatched).append('/').append(REPLACEMENTS).append('\n');

        // S3 같은 내용 재업로드 10회
        Mockito.clearInvocations(s3Client);
        for (int i = 0; i < RESENDS; i++) {
            eventCommand.uploadPoster(eventId, last.clone(), "image/png", ADMIN_ACTOR);
        }
        out.append("S3 같은 내용 재업로드 ").append(RESENDS).append("회: S3 PUT ").append(putCount()).append("회\n");

        // S4 KOPIS 같은 포스터 재전송 10회 + 변경 1회
        Long kopisEventId = eventRepository.save(Event.create("스타더스트 클럽 콘서트 KSPO DOME", SupportedLocale.KO,
                EventType.CONCERT, LocalDate.of(2026, 10, 23))).getId();
        expectKopis("GIF89a-poster-v1".getBytes(StandardCharsets.UTF_8));
        Mockito.clearInvocations(s3Client);
        kopisEnrichment.enrich(TODAY);
        int firstPut = putCount();
        Mockito.clearInvocations(s3Client);
        for (int i = 0; i < RESENDS; i++) {
            kopisEnrichment.enrich(TODAY);
        }
        int resendPut = putCount();
        String before = eventRepository.findById(kopisEventId).orElseThrow().getPosterKey();
        providers.reset();
        expectKopis("GIF89a-poster-v2".getBytes(StandardCharsets.UTF_8));
        kopisEnrichment.enrich(TODAY);
        String after = eventRepository.findById(kopisEventId).orElseThrow().getPosterKey();
        out.append("S4 KOPIS 첫 연결: S3 PUT ").append(firstPut).append("회, poster_key=").append(before).append('\n')
                .append("S4 KOPIS 같은 포스터 재전송 ").append(RESENDS).append("회: S3 PUT ").append(resendPut).append("회\n")
                .append("S4 KOPIS 포스터 변경 1회: ").append(before.equals(after) ? "반영 안 됨" : "반영").append(", poster_key=")
                .append(after).append('\n');
        out.append("KOPIS 출처 연결 수=").append(jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_sources WHERE provider = ?", Integer.class, Provider.KOPIS.name()));

        log.info("{}", out);
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

    private int putCount() {
        return (int) Mockito.mockingDetails(s3Client).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("putObject")).count();
    }

    // PNG 시그니처 + 내용마다 다른 꼬리 (커버 검증은 앞 바이트만 본다)
    static byte[] png(String tail) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] body = tail.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[signature.length + body.length];
        System.arraycopy(signature, 0, out, 0, signature.length);
        System.arraycopy(body, 0, out, signature.length, body.length);
        return out;
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private byte[] get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).body();
    }
}
