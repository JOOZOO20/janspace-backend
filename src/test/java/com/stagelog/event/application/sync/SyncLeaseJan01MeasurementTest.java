package com.stagelog.event.application.sync;

import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.support.IntegrationTest;
import com.stagelog.support.ProjectPaths;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.ReflectionUtils;

/**
 * JAN-01 측정 (docs/measurements/JAN-01.md). 수치만 출력하고 assert 하지 않는다 (수정 전 코드에서도 같은 테스트를 돌린다).
 * 서버 여러 대는 같은 DB 를 보는 SyncRunner 인스턴스 여러 개로 재현한다 (createBean: 인스턴스마다 JVM 안 상태가 따로다).
 */
@Slf4j
@Tag("measurement")
@TestPropertySource(properties = {"jan.sync.lease.ttl-seconds=3", "jan.sync.lease.heartbeat-seconds=1"})
class SyncLeaseJan01MeasurementTest extends IntegrationTest {

    static final int S1_ROUNDS = 100;
    static final int S2_REPEATS = 20;
    static final long BODY_MILLIS = 300;

    @Autowired
    ApplicationContext context;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SyncRunner contextRunner;

    @Test
    void s1_three_instances_trigger_same_source_concurrently() throws Exception {
        List<SyncRunner> runners = List.of(newInstance(), newInstance(), newInstance());
        ExecutorService pool = Executors.newFixedThreadPool(runners.size() + 1);
        int concurrentRounds = 0;
        int runningRounds = 0;
        int wrongCloseRounds = 0;
        int executions = 0;
        try {
            for (int round = 0; round < S1_ROUNDS; round++) {
                long fromId = maxRunId();
                AtomicInteger active = new AtomicInteger();
                AtomicInteger maxActive = new AtomicInteger();
                AtomicInteger executed = new AtomicInteger();
                AtomicInteger maxRunning = new AtomicInteger();
                AtomicBoolean sawFailed = new AtomicBoolean();
                AtomicBoolean done = new AtomicBoolean();
                CyclicBarrier barrier = new CyclicBarrier(runners.size());

                // sampler: 이 라운드 run 중 RUNNING 동시 개수와 FAILED 로 닫힌 것이 있는지 본다
                Future<?> sampler = pool.submit(() -> {
                    while (!done.get()) {
                        sample("KOPIS", fromId, maxRunning, sawFailed);
                        sleep(10);
                    }
                    return null;
                });
                List<Future<SyncResult>> calls = new ArrayList<>();
                for (SyncRunner runner : runners) {
                    calls.add(pool.submit(() -> {
                        barrier.await();
                        return runner.run("KOPIS", () -> {
                            executed.incrementAndGet();
                            maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                            sleep(BODY_MILLIS);
                            active.decrementAndGet();
                            return "측정 body 완료";
                        });
                    }));
                }
                for (Future<SyncResult> call : calls) {
                    call.get(30, TimeUnit.SECONDS);
                }
                done.set(true);
                sampler.get(30, TimeUnit.SECONDS);

                concurrentRounds += maxActive.get() >= 2 ? 1 : 0;
                runningRounds += maxRunning.get() >= 2 ? 1 : 0;
                wrongCloseRounds += sawFailed.get() ? 1 : 0;
                executions += executed.get();
            }
        } finally {
            pool.shutdownNow();
        }
        log.info("[JAN-01 측정 S1] rounds={}, 동시실행={}/{}, 동시RUNNING={}/{}, 정상실행오종료={}/{}, body실행수={} (라운드당 평균 {})",
                S1_ROUNDS, concurrentRounds, S1_ROUNDS, runningRounds, S1_ROUNDS, wrongCloseRounds, S1_ROUNDS,
                executions, (double) executions / S1_ROUNDS);
    }

    @Test
    void s2_reacquire_after_owner_dies_in_process() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        Random random = new Random(20261105L);
        List<Long> reacquireMillis = new ArrayList<>();
        int expiredClosed = 0;
        int resurrected = 0;
        int failedToReacquire = 0;
        try {
            for (int i = 0; i < S2_REPEATS; i++) {
                SyncRunner owner = newInstance();
                SyncRunner other = newInstance();
                long fromId = maxRunId();
                CountDownLatch entered = new CountDownLatch(1);
                CountDownLatch unblock = new CountDownLatch(1);
                Future<SyncResult> ownerCall = pool.submit(() -> owner.run("TICKETMASTER", () -> {
                    entered.countDown();
                    await(unblock);
                    return "이전 소유자 복귀";
                }));
                entered.await(30, TimeUnit.SECONDS);
                Long ownerRunId = jdbc.queryForObject(
                        "SELECT MIN(id) FROM sync_runs WHERE source = 'TICKETMASTER' AND id > ?", Long.class, fromId);

                // 1. 죽이기: Heartbeat 를 멈추고 해제·종료 기록 없이 버린다 (heartbeat 가 없는 수정 전 코드는 멈출 것이 없다)
                sleep(random.nextInt(1500));
                boolean stopped = stopHeartbeats(owner);
                long killedAt = System.nanoTime();

                // 2. 다른 인스턴스가 50ms 마다 시도
                AtomicLong acquiredAt = new AtomicLong();
                while (acquiredAt.get() == 0 && System.nanoTime() - killedAt < TimeUnit.SECONDS.toNanos(15)) {
                    other.run("TICKETMASTER", () -> {
                        acquiredAt.set(System.nanoTime());
                        return "재획득";
                    });
                    if (acquiredAt.get() == 0) {
                        sleep(50);
                    }
                }
                Map<String, Object> atReacquire = runRow(ownerRunId);

                // 3. 이전 소유자 복귀 -> 종료 기록 시도
                unblock.countDown();
                ownerCall.get(30, TimeUnit.SECONDS);
                Map<String, Object> afterReturn = runRow(ownerRunId);

                if (acquiredAt.get() == 0) {
                    failedToReacquire++;
                } else {
                    reacquireMillis.add(TimeUnit.NANOSECONDS.toMillis(acquiredAt.get() - killedAt));
                }
                expiredClosed += "FAILED".equals(atReacquire.get("status")) && "LEASE_EXPIRED".equals(atReacquire.get("summary")) ? 1 : 0;
                resurrected += "OK".equals(afterReturn.get("status")) ? 1 : 0;
                log.info("[JAN-01 측정 S2] i={}, heartbeat중단={}, 재획득ms={}, 재획득시점 이전run={}/{}, 복귀후 이전run={}/{}", i, stopped,
                        acquiredAt.get() == 0 ? "실패" : TimeUnit.NANOSECONDS.toMillis(acquiredAt.get() - killedAt),
                        atReacquire.get("status"), atReacquire.get("summary"), afterReturn.get("status"), afterReturn.get("summary"));
            }
        } finally {
            pool.shutdownNow();
        }
        log.info("[JAN-01 측정 S2] repeats={}, 재획득성공={}/{}, 재획득ms 중앙값={}, 최대={}, 최소={}, LEASE_EXPIRED로닫힘={}/{}, 이전소유자종료기록반영={}/{}",
                S2_REPEATS, S2_REPEATS - failedToReacquire, S2_REPEATS, median(reacquireMillis),
                reacquireMillis.stream().mapToLong(Long::longValue).max().orElse(-1),
                reacquireMillis.stream().mapToLong(Long::longValue).min().orElse(-1),
                expiredClosed, S2_REPEATS, resurrected, S2_REPEATS);
    }

    @Test
    void s3_kill_minus_9_child_process_smoke() throws Exception {
        Path jar = bootJar();
        Assumptions.assumeTrue(jar != null, "build/libs 에 bootJar 가 없다. ./gradlew bootJar 후 실행");

        // 응답하지 않는 Fake 네이버: 자식의 NAVER 동기화가 HTTP 호출에서 멈춰 Lease 를 쥔 채로 있게 한다
        HttpServer fakeNaver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeNaver.createContext("/", exchange -> sleep(TimeUnit.MINUTES.toMillis(5)));
        fakeNaver.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-naver");
            t.setDaemon(true);
            return t;
        }));
        fakeNaver.start();

        long fromId = maxRunId();
        File childLog = ProjectPaths.projectRoot().resolve("build/jan01-child.log").toFile();
        Process child = new ProcessBuilder(javaBinary(), "-Xmx384m", "-jar", jar.toString(),
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--server.port=0",
                "--stagelog.jwt.secret=stagelog-test-jwt-secret-0123456789abcdef",
                "--stagelog.naver-calendar.published-key=jan01-smoke",
                "--stagelog.naver-calendar.schedule-list-uri=http://127.0.0.1:" + fakeNaver.getAddress().getPort() + "/schedules",
                "--stagelog.sync.cron.naver=* * * * * *",
                "--stagelog.push.dispatch-cron=-",
                "--stagelog.push.resync-cron=-",
                "--spring.http.clients.read-timeout=300s",
                "--jan.sync.lease.ttl-seconds=5",
                "--jan.sync.lease.heartbeat-seconds=1")
                .redirectErrorStream(true).redirectOutput(childLog).start();
        try {
            // 1. 자식이 NAVER run 을 RUNNING 으로 남길 때까지 (기동 포함 최대 180초)
            Long childRunId = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            while (childRunId == null && System.nanoTime() < deadline && child.isAlive()) {
                childRunId = jdbc.queryForObject("SELECT MIN(id) FROM sync_runs WHERE source = 'NAVER' AND status = 'RUNNING' AND id > ?",
                        Long.class, fromId);
                sleep(200);
            }
            Assumptions.assumeTrue(childRunId != null, "자식 프로세스가 NAVER 동기화를 시작하지 못했다. 로그: " + childLog);
            // Heartbeat 가 한두 번 돌게 둔다
            sleep(2500);
            log.info("[JAN-01 측정 S3] 자식 pid={}, 자식run={}, lease={}", child.pid(), runRow(childRunId), leaseRow("sync:NAVER"));

            // 2. kill -9 (Process.destroyForcibly 는 Unix 에서 SIGKILL)
            child.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
            long killedAt = System.nanoTime();

            // 3. 테스트 JVM 의 SyncRunner 가 100ms 마다 시도
            AtomicLong acquiredAt = new AtomicLong();
            while (acquiredAt.get() == 0 && System.nanoTime() - killedAt < TimeUnit.SECONDS.toNanos(60)) {
                contextRunner.run("NAVER", () -> {
                    acquiredAt.set(System.nanoTime());
                    return "kill -9 후 재획득";
                });
                if (acquiredAt.get() == 0) {
                    sleep(100);
                }
            }
            log.info("[JAN-01 측정 S3] 재획득={}, 재획득ms={}, 자식run={}", acquiredAt.get() != 0,
                    acquiredAt.get() == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(acquiredAt.get() - killedAt), runRow(childRunId));
        } finally {
            child.destroyForcibly();
            fakeNaver.stop(0);
        }
    }

    private SyncRunner newInstance() {
        return context.getAutowireCapableBeanFactory().createBean(SyncRunner.class);
    }

    // 수정 후 코드의 Heartbeat 실행기를 멈춘다. 필드가 없으면(수정 전) false
    private static boolean stopHeartbeats(SyncRunner runner) {
        Field field = ReflectionUtils.findField(SyncRunner.class, "heartbeats");
        if (field == null) {
            return false;
        }
        ReflectionUtils.makeAccessible(field);
        ((ScheduledExecutorService) ReflectionUtils.getField(field, runner)).shutdownNow();
        return true;
    }

    private void sample(String source, long fromId, AtomicInteger maxRunning, AtomicBoolean sawFailed) {
        jdbc.query("SELECT SUM(status = 'RUNNING'), SUM(status = 'FAILED') FROM sync_runs WHERE source = ? AND id > ?", rs -> {
            maxRunning.accumulateAndGet(rs.getInt(1), Math::max);
            if (rs.getInt(2) > 0) {
                sawFailed.set(true);
            }
        }, source, fromId);
    }

    private long maxRunId() {
        Long id = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM sync_runs", Long.class);
        return id == null ? 0 : id;
    }

    private Map<String, Object> runRow(Long id) {
        return jdbc.queryForMap("SELECT id, status, summary FROM sync_runs WHERE id = ?", id);
    }

    private Object leaseRow(String key) {
        try {
            return jdbc.queryForList("SELECT * FROM job_leases WHERE lease_key = ?", key);
        } catch (RuntimeException e) {
            return "job_leases 없음";
        }
    }

    private static long median(List<Long> values) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static Path bootJar() throws Exception {
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
