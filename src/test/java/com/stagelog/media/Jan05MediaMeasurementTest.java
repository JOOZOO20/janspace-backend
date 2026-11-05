package com.stagelog.media;

import com.stagelog.media.Jan05Samples.Sample;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;

/**
 * JAN-05 측정 S1(목록 화면 전송량), S2(변환 중 Worker 강제 종료 50회), S3 조건 A(백필 없음)·C(백필 + Queue 분리 켬).
 * 조건 B(분리 끔)는 설정이 달라 Jan05SplitOffMeasurementTest 가 같은 절차로 잰다.
 */
@Slf4j
@TestMethodOrder(MethodOrderer.MethodName.class)
@TestPropertySource(properties = "jan.media.queue.split=true")
class Jan05MediaMeasurementTest extends Jan05MeasurementBase {

    static final int S1_RUNS = 3;
    static final int S2_KILLS = 50;
    static final int S2_BATCH = 10;
    // 회수 대기 시간 상한 = Lease 90초 + Zombie 회수 주기 60초 + 변환 제한 60초 (판정 기준)
    static final long S2_RECOVERY_LIMIT_SECONDS = 210;
    static final int S3_RUNS = 3;

    @Test
    void s1_list_screen_transfer_bytes() throws Exception {
        List<Sample> samples = Jan05Samples.load();
        boolean pipeline = pipelinePresent();
        log.info("[JAN-05 측정 S1] 샘플={}장, 원본 합계 bytes={}, 변환 파이프라인={}", samples.size(),
                samples.stream().mapToLong(s -> s.bytes().length).sum(), pipeline ? "있음" : "없음");
        Map<Integer, String> firstRunHashes = null;
        for (int run = 1; run <= S1_RUNS; run++) {
            resetData();
            // 1. 샘플 n 을 행사 날짜 순서 n 인 행사 포스터로 올린다 (목록 API 는 날짜 오름차순 20개씩)
            LocalDate base = LocalDate.of(2027, 1, 1);
            for (Sample s : samples) {
                newEventWithPoster("S1-" + s.n(), base.plusDays(s.n()), s.bytes(), s.contentType());
            }
            // 2. 수정 후: 100장의 rendition 이 모두 끝날 때까지 (최대 30분)
            if (pipeline) {
                long deadline = System.nanoTime() + 30L * 60 * 1_000_000_000L;
                int done = 0;
                while (System.nanoTime() < deadline) {
                    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM media_assets WHERE status <> 'PENDING'", Integer.class);
                    done = n == null ? 0 : n;
                    if (done >= samples.size()) {
                        break;
                    }
                    sleep(1000);
                }
                log.info("[JAN-05 측정 S1] run={}, 변환 끝난 asset={}/{}", run, done, samples.size());
            }
            // 3. 화면(20장)마다 목록 API 가 주는 URL 의 실제 bytes
            long totalOriginal = 0;
            long totalWebp = 0;
            boolean webpAvailable = true;
            Map<Integer, String> hashes = new TreeMap<>();
            for (int screen = 0; screen < 5; screen++) {
                long original = 0;
                long webp = 0;
                int converted = 0;
                for (JsonNode item : listPage(screen)) {
                    String posterUrl = item.path("posterUrl").asString();
                    original += httpSize(posterUrl);
                    JsonNode list = item.path("images").path("list").path("webp");
                    if (list.isMissingNode() || list.isNull()) {
                        webpAvailable = false;
                        continue;
                    }
                    String url = list.asString();
                    webp += httpSize(url);
                    if (!url.equals(posterUrl)) {
                        converted++;
                        String file = url.substring(url.lastIndexOf('/') + 1);
                        hashes.put(Integer.parseInt(item.path("name").path("original").asString().substring(3)), file.replaceAll("^list-|\\.webp$", ""));
                    }
                }
                totalOriginal += original;
                totalWebp += webp;
                log.info("[JAN-05 측정 S1] run={}, 화면={}, 원본bytes={}, listWebpBytes={}, 변환된자리={}/20, 감소율={}", run, screen + 1, original,
                        webpAvailable ? webp : "N/A", webpAvailable ? converted : "N/A", webpAvailable ? ratio(original, webp) : "N/A");
            }
            log.info("[JAN-05 측정 S1] run={}, 100장 합계 원본bytes={}, listWebpBytes={}, 감소율={}", run, totalOriginal,
                    webpAvailable ? totalWebp : "N/A", webpAvailable ? ratio(totalOriginal, totalWebp) : "N/A");
            if (webpAvailable) {
                if (firstRunHashes == null) {
                    firstRunHashes = hashes;
                } else {
                    log.info("[JAN-05 측정 S1] run={}, list WebP 출력 hash 가 run 1 과 같음={}", run, firstRunHashes.equals(hashes));
                }
            }
        }
    }

    @Test
    void s2_worker_killed_during_transcode() throws Exception {
        if (!pipelinePresent()) {
            log.info("[JAN-05 측정 S2] N/A: 변환 Worker({}) 가 없다 (파이프라인 없음)", HANDLER_BEAN);
            return;
        }
        List<Sample> posters = Jan05Samples.load().subList(0, S2_KILLS);
        resetData();
        int duplicates = 0;
        int missing = 0;
        int objectsMissing = 0;
        List<Long> recoveryMillis = new ArrayList<>();
        Map<Integer, Integer> attempts = new TreeMap<>();
        try (S3Client s3 = adminS3()) {
            for (int batch = 0; batch < S2_KILLS / S2_BATCH; batch++) {
                // 1. 원본 10장을 올리고, 각 원본의 첫 변환 호출에서 Worker 를 강제 종료한다
                Map<Long, String> eventSha = new HashMap<>();
                for (Sample s : posters.subList(batch * S2_BATCH, (batch + 1) * S2_BATCH)) {
                    String sha = sha256(s.bytes());
                    KILL_TARGETS.add(sha);
                    eventSha.put(newEventWithPoster("S2-" + s.n(), LocalDate.of(2027, 6, 1).plusDays(s.n()), s.bytes(), s.contentType()), sha);
                }
                long killWait = System.nanoTime() + 120_000_000_000L;
                while (!KILL_TARGETS.isEmpty() && System.nanoTime() < killWait) {
                    sleep(200);
                }
                Instant lastKill = KILLED_AT.values().stream().max(Instant::compareTo).orElse(Instant.now());
                // 2. 마지막 종료 + 회수 상한까지 모든 rendition 이 끝나기를 기다린다
                Instant deadline = lastKill.plusSeconds(S2_RECOVERY_LIMIT_SECONDS);
                String in = eventSha.keySet().stream().map(String::valueOf).collect(Collectors.joining(","));
                while (Instant.now().isBefore(deadline)) {
                    Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM media_renditions r JOIN media_assets a ON a.id = r.asset_id"
                            + " WHERE a.owner_id IN (" + in + ") AND r.status IN ('PENDING', 'PROCESSING')", Integer.class);
                    if (open != null && open == 0) {
                        break;
                    }
                    sleep(1000);
                }
                // 3. asset 마다 판정 지표
                for (Map.Entry<Long, String> e : eventSha.entrySet()) {
                    Map<String, Object> row = jdbc.queryForMap("""
                            SELECT a.id AS asset_id, SUM(r.status = 'READY') AS ready, SUM(r.status IN ('PENDING', 'PROCESSING')) AS open,
                                   MAX(r.attempt) AS attempt, TIMESTAMPDIFF(MICROSECOND, '1970-01-01', MAX(r.updated_at)) AS last_us
                              FROM media_assets a JOIN media_renditions r ON r.asset_id = a.id
                             WHERE a.owner_type = 'EVENT_POSTER' AND a.owner_id = ? GROUP BY a.id""", e.getKey());
                    long assetId = ((Number) row.get("asset_id")).longValue();
                    missing += ((Number) row.get("open")).intValue();
                    attempts.merge(((Number) row.get("attempt")).intValue(), 1, Integer::sum);
                    Instant killed = KILLED_AT.get(e.getValue());
                    if (((Number) row.get("open")).intValue() == 0 && killed != null) {
                        recoveryMillis.add(((Number) row.get("last_us")).longValue() / 1000 - killed.toEpochMilli());
                    }
                    // 같은 (asset, profile, format) 의 READY 결과: 행(유일 제약) + S3 객체(출력 hash 가 다르면 객체가 여럿)
                    List<Map<String, Object>> ready = jdbc.queryForList(
                            "SELECT profile, format, output_key FROM media_renditions WHERE asset_id = ? AND status = 'READY'", assetId);
                    Map<String, Long> objects = s3.listObjectsV2(b -> b.bucket(BUCKET).prefix("media/" + assetId + "/")).contents().stream()
                            .map(S3Object::key).collect(Collectors.groupingBy(k -> k.substring(k.lastIndexOf('/') + 1, k.indexOf('-', k.lastIndexOf('/')))
                                    + k.substring(k.lastIndexOf('.')), Collectors.counting()));
                    duplicates += (int) objects.values().stream().filter(c -> c > 1).count();
                    for (Map<String, Object> r : ready) {
                        String key = (String) r.get("output_key");
                        try {
                            s3.headObject(b -> b.bucket(BUCKET).key(key));
                        } catch (RuntimeException ex) {
                            objectsMissing++;
                        }
                    }
                    duplicates += jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT asset_id FROM media_renditions WHERE asset_id = ? AND status = 'READY'"
                            + " GROUP BY asset_id, profile, format HAVING COUNT(*) > 1) d", Integer.class, assetId);
                }
                log.info("[JAN-05 측정 S2] batch={}, 종료={}건, 누적 중복READY={}, 누적 누락={}, 누적 READY 객체 없음={}", batch + 1,
                        KILLED_AT.size(), duplicates, missing, objectsMissing);
                KILLED_AT.clear();
            }
        }
        List<Long> sorted = recoveryMillis.stream().sorted().toList();
        log.info("[JAN-05 측정 S2] 강제 종료={}회, 중복 READY={}, 누락={}, READY 객체 없음={}, 회수 시간 ms p50={}, p95={}, max={}, 회수된 asset={}, "
                        + "asset 별 최대 attempt 분포={}", S2_KILLS, duplicates, missing, objectsMissing, percentile(sorted, 50), percentile(sorted, 95),
                sorted.isEmpty() ? null : sorted.get(sorted.size() - 1), sorted.size(), attempts);
    }

    @Test
    void s3a_new_uploads_without_backfill() throws Exception {
        for (int run = 1; run <= S3_RUNS; run++) {
            backfillScenario("A(백필 없음)", false, run);
        }
    }

    @Test
    void s3c_new_uploads_during_backfill_split_on() throws Exception {
        for (int run = 1; run <= S3_RUNS; run++) {
            backfillScenario("C(백필 1만 + 분리 켬)", true, run);
        }
    }

    static String ratio(long original, long converted) {
        return original == 0 ? "N/A" : String.format("%.1f%%", 100.0 * (original - converted) / original);
    }
}
