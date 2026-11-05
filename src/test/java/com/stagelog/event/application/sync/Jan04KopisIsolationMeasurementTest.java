package com.stagelog.event.application.sync;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventType;
import com.stagelog.ops.application.SyncRunQuery;
import com.stagelog.support.IntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;

/**
 * JAN-04 측정 S1 (docs/measurements/JAN-04.md): KOPIS 응답 50건 중 무작위 위치 1건 파싱 오류. 수치만 출력하고 assert 하지 않는다
 * (수정 전 코드에서도 같은 테스트를 돌린다). 공개 진입점 KopisEnrichment.enrich 만 쓴다.
 */
@Slf4j
@Tag("measurement")
class Jan04KopisIsolationMeasurementTest extends IntegrationTest {

    static final int ITEMS = 50;
    static final int REPEATS = 50;
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    static final String KOPIS = "https://www.kopis.or.kr/openApi/restful";
    static final String KEY = "service=test-kopis-key";
    static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    static final List<String> TABLES = List.of("event_proposals", "event_source_changes", "event_discoveries",
            "event_match_candidates", "event_sources", "event_translations", "events", "venue_sources",
            "venue_translations", "venues", "sync_runs", "sync_item_failures");

    @Autowired
    KopisEnrichment enrichment;
    @Autowired
    EventRepository eventRepository;
    @Autowired
    SyncRunQuery syncRunQuery;
    @Autowired
    MockRestServiceServer providers;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void s1_one_broken_item_out_of_fifty() {
        Random random = new Random(20261105L);
        double ratioSum = 0;
        double ratioMin = 1;
        int partial = 0;
        int oneFailureRecorded = 0;
        int cursorKept = 0;
        Map<String, Integer> statuses = new java.util.TreeMap<>();
        for (int round = 0; round < REPEATS; round++) {
            clean();
            int broken = random.nextInt(ITEMS);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < ITEMS; i++) {
                ids.add(eventRepository.save(Event.create("스타더스트 공연 " + i, SupportedLocale.KO, EventType.CONCERT, date(i))).getId());
            }
            stubKopis(broken);
            // 커서 기준: 앞선 OK 실행 1개를 심는다 (이번 실행이 OK 면 이 값보다 뒤로 전진한다)
            jdbc.update("INSERT INTO sync_runs (source, started_at, finished_at, status, summary, created_at, updated_at)"
                    + " VALUES ('KOPIS', NOW(6) - INTERVAL 1 DAY, NOW(6) - INTERVAL 1 DAY, 'OK', 'seed', NOW(6), NOW(6))");
            Optional<Instant> cursorBefore = syncRunQuery.lastSucceededAt("KOPIS");

            enrichment.enrich(TODAY);

            int saved = 0;
            for (int i = 0; i < ITEMS; i++) {
                if (i == broken) {
                    continue;
                }
                Integer linked = jdbc.queryForObject("SELECT COUNT(*) FROM event_sources WHERE event_id = ? AND provider = 'KOPIS'",
                        Integer.class, ids.get(i));
                saved += linked != null && linked > 0 ? 1 : 0;
            }
            double ratio = saved / (double) (ITEMS - 1);
            ratioSum += ratio;
            ratioMin = Math.min(ratioMin, ratio);
            Map<String, Object> run = jdbc.queryForMap(
                    "SELECT id, status FROM sync_runs WHERE source = 'KOPIS' AND summary <> 'seed' ORDER BY id DESC LIMIT 1");
            String status = String.valueOf(run.get("status"));
            statuses.merge(status, 1, Integer::sum);
            partial += "PARTIAL".equals(status) ? 1 : 0;
            int failures = itemFailures(((Number) run.get("id")).longValue());
            oneFailureRecorded += failures == 1 ? 1 : 0;
            boolean kept = cursorBefore.equals(syncRunQuery.lastSucceededAt("KOPIS"));
            cursorKept += kept ? 1 : 0;
            log.info("[JAN-04 측정 S1] round={}, 오류위치={}, 저장={}/{}, 상태={}, 실패기록={}, 커서미전진={}", round, broken, saved,
                    ITEMS - 1, status, failures, kept);
        }
        log.info("[JAN-04 측정 S1] repeats={}, 저장비율 평균={}%, 최소={}%, 상태={}, PARTIAL={}/{}, 실패기록1건={}/{}, 커서미전진={}/{}",
                REPEATS, String.format("%.1f", ratioSum / REPEATS * 100), String.format("%.1f", ratioMin * 100), statuses,
                partial, REPEATS, oneFailureRecorded, REPEATS, cursorKept, REPEATS);
    }

    // 행사 i 의 날짜와 공연 PF95{i}. 목록은 행사 날짜로 검색되고, 상세 하나만 잘린 XML 이다. 포스터·시설은 없다(다운로드·지오코딩 없음)
    private void stubKopis(int broken) {
        providers.reset();
        for (int i = 0; i < ITEMS; i++) {
            String id = mt20id(i);
            String day = YYYYMMDD.format(date(i));
            providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith(KOPIS + "/pblprfr?" + KEY + "&stdate=" + day + "&eddate=" + day)))
                    .andRespond(withSuccess(listXml(i), MediaType.APPLICATION_XML));
            String detail = i == broken ? "<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs><db><mt20id>" + id + "</mt20id><prfnm>잘린"
                    : detailXml(i);
            providers.expect(ExpectedCount.manyTimes(), requestTo(KOPIS + "/pblprfr/" + id + "?" + KEY))
                    .andRespond(withSuccess(detail, MediaType.APPLICATION_XML));
        }
    }

    private static String listXml(int i) {
        String d = DOTTED.format(date(i));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs><db><mt20id>" + mt20id(i) + "</mt20id><prfnm>스타더스트 클럽 공연 " + i
                + "</prfnm><prfpdfrom>" + d + "</prfpdfrom><prfpdto>" + d + "</prfpdto></db></dbs>";
    }

    private static String detailXml(int i) {
        String d = DOTTED.format(date(i));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><dbs><db><mt20id>" + mt20id(i) + "</mt20id><prfnm>스타더스트 클럽 공연 " + i
                + "</prfnm><prfpdfrom>" + d + "</prfpdfrom><prfpdto>" + d + "</prfpdto><prfruntime>2시간</prfruntime></db></dbs>";
    }

    private static String mt20id(int i) {
        return "PF95" + String.format("%04d", i);
    }

    private static LocalDate date(int i) {
        return TODAY.plusDays(1 + i);
    }

    // sync_item_failures 가 없는 수정 전 코드는 0
    private int itemFailures(long runId) {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sync_item_failures WHERE sync_run_id = ?", Integer.class, runId);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // sync_runs 를 비우면 실행 ID 가 다시 1 부터라 sync_item_failures 도 같이 비운다 (수정 전 코드에는 테이블이 없어 건너뛴다)
    private void clean() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        TABLES.forEach(t -> {
            try {
                jdbc.execute("TRUNCATE TABLE " + t);
            } catch (RuntimeException e) {
                log.info("[JAN-04 측정 S1] 테이블 없음, 건너뜀 table={}", t);
            }
        });
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbc.update("UPDATE job_leases SET owner_id = NULL, expires_at = NULL");
    }
}
