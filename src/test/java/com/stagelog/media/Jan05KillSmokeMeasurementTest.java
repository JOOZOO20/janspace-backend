package com.stagelog.media;

import com.stagelog.ops.application.QueueConsumer;
import com.stagelog.ops.application.QueueHandler;
import com.stagelog.ops.application.port.MessageQueuePort;
import com.stagelog.ops.application.port.MessageQueuePort.Received;
import com.stagelog.support.FfmpegContainer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * JAN-05 측정 S2 smoke: 실제 프로세스 kill -9 1회. bootJar 자식 프로세스가 rendition 을 claim 해 FFmpeg 를 돌리는 중에 SIGKILL 하고,
 * 이 테스트 JVM 이 Zombie 회수(1분 주기)와 SQS 재전달로 같은 asset 을 끝내는지 본다. 자식이 먼저 claim 하도록 이 JVM 의 polling thread 는 끄고
 * (jan.queue.consumer.threads=0) 종료 뒤에는 테스트가 직접 받아 처리한다. 자식의 FFmpeg 앞에는 30초 sleep 을 둬 처리 중 상태를 만든다.
 */
@Slf4j
@TestPropertySource(properties = "jan.queue.consumer.threads=0")
class Jan05KillSmokeMeasurementTest extends Jan05MeasurementBase {

    @Autowired
    MessageQueuePort queue;
    @Autowired
    QueueConsumer consumer;

    @Test
    void s2_kill_minus_9_child_process_smoke() throws Exception {
        if (!pipelinePresent()) {
            log.info("[JAN-05 측정 S2 smoke] N/A: 변환 Worker({}) 가 없다 (파이프라인 없음)", HANDLER_BEAN);
            return;
        }
        Path jar = bootJar();
        Assumptions.assumeTrue(jar != null, "build/libs 에 bootJar 가 없다. ./gradlew bootJar 후 실행");
        resetData();
        QueueHandler handler = context.getBean(HANDLER_BEAN, QueueHandler.class);
        String container = FfmpegContainer.CONTAINER.getContainerId();
        File childLog = projectFile("build/jan05-child.log").toFile();
        Process child = new ProcessBuilder(javaBinary(), "-Xmx384m",
                "-Daws.accessKeyId=" + LOCALSTACK.getAccessKey(), "-Daws.secretAccessKey=" + LOCALSTACK.getSecretKey(),
                "-jar", jar.toString(),
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--server.port=0",
                "--stagelog.jwt.secret=stagelog-test-jwt-secret-0123456789abcdef",
                "--stagelog.aws.endpoint=" + LOCALSTACK.getEndpoint(),
                "--stagelog.storage.region=" + LOCALSTACK.getRegion(),
                "--stagelog.storage.bucket=" + BUCKET,
                "--stagelog.storage.cdn-base-url=" + bucketUrl(),
                "--stagelog.sync.cron.kopis=-", "--stagelog.sync.cron.naver=-", "--stagelog.sync.cron.ticketmaster=-",
                "--stagelog.sync.cron.setlistfm=-", "--stagelog.push.dispatch-cron=-", "--stagelog.push.resync-cron=-",
                "--jan.storage.cleanup-cron=-", "--jan.youtube.quota.reset-cron=-",
                "--jan.pipeline.async-apply.enabled=false",
                "--jan.media.transcode.enabled=true",
                "--jan.media.worker.concurrency=1",
                "--jan.media.ffmpeg.command=sh,-c,sleep 30; exec docker exec -i " + container + " ffmpeg \"$@\",sh",
                "--jan.media.ffmpeg.probe-command=docker,exec,-i," + container + ",ffprobe",
                "--jan.queue.relay.enabled=false",
                "--jan.queue.auto-create=false",
                "--jan.queue.consumer.threads=1",
                "--jan.queue.consumer.wait-seconds=1")
                .redirectErrorStream(true).redirectOutput(childLog).start();
        try {
            // 1. 원본 1장 등록 (이 JVM 의 Relay 가 발행). 자식이 rendition 하나를 claim 해 처리 중이 될 때까지 (기동 포함 최대 240초)
            Jan05Samples.Sample poster = Jan05Samples.load().get(0);
            long eventId = newEventWithPoster("S2-smoke", LocalDate.of(2029, 1, 1), poster.bytes(), poster.contentType());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
            Map<String, Object> processing = null;
            while (System.nanoTime() < deadline && child.isAlive()) {
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, attempt FROM media_renditions WHERE status = 'PROCESSING'");
                if (!rows.isEmpty()) {
                    processing = rows.get(0);
                    break;
                }
                sleep(500);
            }
            Assumptions.assumeTrue(processing != null, "자식 프로세스가 rendition 을 claim 하지 못했다. 로그: " + childLog);
            long renditionId = ((Number) processing.get("id")).longValue();
            sleep(2000);

            // 2. kill -9 (Process.destroyForcibly 는 Unix 에서 SIGKILL). 자식이 띄운 sleep·docker exec 도 같이 정리한다
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
            Instant killedAt = Instant.now();

            // 3. 이 JVM 이 메시지를 받아 처리한다. 종료된 rendition 은 Lease 만료 뒤 재전달 또는 Zombie 회수 재발행으로 다시 온다
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
            int open = Integer.MAX_VALUE;
            while (System.nanoTime() < end) {
                for (Received message : queue.receive(TRANSCODE_QUEUE, 1, 1, null)) {
                    consumer.process(handler, message);
                }
                Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM media_renditions WHERE status IN ('PENDING', 'PROCESSING')", Integer.class);
                open = n == null ? -1 : n;
                if (open == 0) {
                    break;
                }
            }
            Map<String, Object> killed = jdbc.queryForMap("SELECT status, attempt, output_key,"
                    + " TIMESTAMPDIFF(MICROSECOND, '1970-01-01', updated_at) AS us FROM media_renditions WHERE id = ?", renditionId);
            Integer ready = jdbc.queryForObject("SELECT COUNT(*) FROM media_renditions WHERE status = 'READY'", Integer.class);
            Integer duplicates = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT asset_id FROM media_renditions WHERE status = 'READY'"
                    + " GROUP BY asset_id, profile, format HAVING COUNT(*) > 1) d", Integer.class);
            long recovery = ((Number) killed.get("us")).longValue() / 1000 - killedAt.toEpochMilli();
            log.info("[JAN-05 측정 S2 smoke] eventId={}, kill 시 처리 중 renditionId={}, kill 후 상태={}, attempt={}, 회수 시간 ms={}, READY={}/6,"
                            + " 중복 READY={}, 미완료={}", eventId, renditionId, killed.get("status"), killed.get("attempt"), recovery, ready,
                    duplicates, open);
        } finally {
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
        }
    }

    private static Path bootJar() throws IOException {
        Path libs = projectFile("build/libs");
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
}
