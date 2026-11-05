package com.stagelog.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.SourceChangeField;
import com.stagelog.event.domain.SourceChangeStatus;
import com.stagelog.ops.application.JobLeases;
import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.ops.application.SyncRunRecorder;
import com.stagelog.ops.domain.SyncRun;
import com.stagelog.ops.domain.SyncStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * JAN-01 수용 기준. 서버 여러 대는 같은 DB 를 보는 SyncRunner 인스턴스 여러 개(인스턴스마다 owner_id·Heartbeat 스레드가 따로)로 재현한다.
 * TTL 은 테스트 시간을 줄이려고 2초로 둔다.
 */
class SyncLeaseIntegrationTest extends SyncIntegrationTest {

    static final long TTL = 2;

    @Autowired
    SyncRunRecorder recorder;
    @Autowired
    OperatorAlert operatorAlert;
    @Autowired
    JobLeases leases;
    @Autowired
    ApplySourcesUseCase applySources;

    final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    SyncRunner instance(long ttlSeconds, long heartbeatSeconds) {
        return new SyncRunner(recorder, operatorAlert, leases, new SyncLeaseProperties(true, ttlSeconds, heartbeatSeconds));
    }

    @Test
    @DisplayName("인스턴스 3개가 같은 공급원을 동시에 시작하는 시도 100회: 본문 동시 실행·RUNNING 동시 2개·정상 run 의 FAILED 가 모두 0회")
    void three_instances_never_run_same_source_concurrently() throws Exception {
        List<SyncRunner> runners = List.of(instance(120, 30), instance(120, 30), instance(120, 30));
        int concurrentRounds = 0;
        int runningRounds = 0;
        int failedRounds = 0;
        for (int round = 0; round < 100; round++) {
            long fromId = maxRunId();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            AtomicInteger maxRunning = new AtomicInteger();
            AtomicBoolean sawFailed = new AtomicBoolean();
            AtomicBoolean done = new AtomicBoolean();
            CyclicBarrier barrier = new CyclicBarrier(runners.size());
            Future<?> sampler = pool.submit(() -> {
                while (!done.get()) {
                    jdbc.query("SELECT SUM(status = 'RUNNING'), SUM(status = 'FAILED') FROM sync_runs WHERE source = 'KOPIS' AND id > ?",
                            rs -> {
                                maxRunning.accumulateAndGet(rs.getInt(1), Math::max);
                                sawFailed.compareAndSet(false, rs.getInt(2) > 0);
                            }, fromId);
                    Thread.sleep(5);
                }
                return null;
            });
            List<Future<SyncResult>> calls = new ArrayList<>();
            for (SyncRunner runner : runners) {
                calls.add(pool.submit(() -> {
                    barrier.await();
                    return runner.run("KOPIS", () -> {
                        maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                        sleep(100);
                        active.decrementAndGet();
                        return "ok";
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
            failedRounds += sawFailed.get() ? 1 : 0;
        }

        assertThat(concurrentRounds).as("본문 동시 실행 라운드").isZero();
        assertThat(runningRounds).as("RUNNING 동시 2개 이상 라운드").isZero();
        assertThat(failedRounds).as("정상 run 이 FAILED 로 닫힌 라운드").isZero();
        assertThat(runs()).hasSizeGreaterThanOrEqualTo(100).allMatch(r -> r.getStatus() == SyncStatus.OK);
    }

    @Test
    @DisplayName("소유 인스턴스가 죽으면(Heartbeat 없음, 해제 없음) TTL 뒤 다른 인스턴스가 획득하고 이전 run 은 LEASE_EXPIRED 로 닫힌다. "
            + "뒤늦게 복귀한 이전 소유자의 OK 기록은 반영되지 않는다")
    void dead_owner_is_taken_over_after_ttl_and_late_finish_is_fenced() throws Exception {
        // Heartbeat 주기(60초)가 TTL(2초)보다 길다 = 획득 직후 죽은 프로세스와 같은 DB 상태
        SyncRunner dead = instance(TTL, 60);
        SyncRunner other = instance(TTL, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch revive = new CountDownLatch(1);
        Future<SyncResult> deadCall = pool.submit(() -> dead.run("TICKETMASTER", () -> {
            entered.countDown();
            await(revive);
            return "죽었다 살아난 소유자";
        }));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Long deadRun = onlyRunId();
        long startedAt = System.nanoTime();

        // 1. 만료 전에는 획득하지 못한다
        assertThat(other.run("TICKETMASTER", () -> "너무 이르다").busy()).isTrue();

        // 2. TTL 뒤 획득
        AtomicLong acquiredAfter = new AtomicLong();
        SyncResult takeover = null;
        while (acquiredAfter.get() == 0 && System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(10)) {
            takeover = other.run("TICKETMASTER", () -> {
                acquiredAfter.set(System.nanoTime() - startedAt);
                return "이어받음";
            });
            if (acquiredAfter.get() == 0) {
                sleep(100);
            }
        }
        assertThat(acquiredAfter.get()).as("재획득").isPositive();
        assertThat(TimeUnit.NANOSECONDS.toMillis(acquiredAfter.get())).isBetween(TTL * 1000 - 500, TTL * 1000 + 1500);
        assertThat(takeover.ok()).isTrue();
        SyncRun expired = syncRuns.findById(deadRun).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(expired.getSummary()).isEqualTo(SyncRunRecorder.LEASE_EXPIRED);

        // 3. 이전 소유자 복귀: 종료 기록(OK)이 token 불일치로 거부된다
        revive.countDown();
        deadCall.get(10, TimeUnit.SECONDS);
        SyncRun afterReturn = syncRuns.findById(deadRun).orElseThrow();
        assertThat(afterReturn.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(afterReturn.getSummary()).isEqualTo(SyncRunRecorder.LEASE_EXPIRED);
        assertThat(runs()).filteredOn(r -> !r.getId().equals(deadRun)).singleElement()
                .satisfies(r -> assertThat(r.getStatus()).isEqualTo(SyncStatus.OK));
    }

    @Test
    @DisplayName("Heartbeat 를 TTL 보다 늦게 주입하면 다른 인스턴스가 이어받고, 늦은 Heartbeat 가 상실을 확인한 이전 소유자는 즉시 멈추고 종료 기록을 남기지 않는다")
    void delayed_heartbeat_detects_loss_and_stops_writes() throws Exception {
        // Heartbeat 3초 > TTL 2초: 첫 Heartbeat 전에 Lease 가 만료된다
        SyncRunner slow = instance(TTL, 3);
        SyncRunner other = instance(TTL, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch takenOver = new CountDownLatch(1);
        AtomicInteger itemsAfterLoss = new AtomicInteger();
        Future<SyncResult> slowCall = pool.submit(() -> slow.run("SETLISTFM", () -> {
            entered.countDown();
            await(takenOver);
            // 동기화 루프처럼 항목마다 Lease 를 확인한다. 늦은 Heartbeat(획득 후 3초)가 영향 행 0 을 보면 여기서 멈춘다
            for (int item = 0; item < 100; item++) {
                slow.ensureHeld();
                itemsAfterLoss.incrementAndGet();
                sleep(100);
            }
            return "멈추지 않았다";
        }));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Long slowRun = onlyRunId();

        AtomicBoolean acquired = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!acquired.get() && System.nanoTime() < deadline) {
            other.run("SETLISTFM", () -> {
                acquired.set(true);
                return "이어받음";
            });
            sleep(100);
        }
        assertThat(acquired).isTrue();
        takenOver.countDown();

        SyncResult slowResult = slowCall.get(20, TimeUnit.SECONDS);
        assertThat(slowResult.failed()).isTrue();
        assertThat(slowResult.summary()).isEqualTo(SyncRunner.LEASE_LOST);
        // 상실 확인까지 최대 한 Heartbeat 주기(3초) 동안만 항목을 처리했다
        assertThat(itemsAfterLoss.get()).isLessThan(40);
        SyncRun fenced = syncRuns.findById(slowRun).orElseThrow();
        assertThat(fenced.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(fenced.getSummary()).isEqualTo(SyncRunRecorder.LEASE_EXPIRED);
    }

    @Test
    @DisplayName("Heartbeat 가 도는 정상 실행은 TTL 을 넘겨도 다른 인스턴스가 가져가거나 닫지 못한다 (Lease 없는 같은 출처 run 시작 포함)")
    void healthy_owner_keeps_lease_beyond_ttl_and_is_never_closed() throws Exception {
        SyncRunner owner = instance(TTL, 1);
        SyncRunner other = instance(TTL, 1);
        CountDownLatch entered = new CountDownLatch(1);
        Future<SyncResult> ownerCall = pool.submit(() -> owner.run("SETLISTFM", () -> {
            entered.countDown();
            sleep(TTL * 1000 * 4);
            return "정상 완료";
        }));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        Long ownerRun = onlyRunId();

        int busy = 0;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(TTL * 2 + 1);
        while (System.nanoTime() < until) {
            busy += other.run("SETLISTFM", () -> "가져가면 안 됨").busy() ? 1 : 0;
            assertThat(syncRuns.findById(ownerRun).orElseThrow().getStatus()).isEqualTo(SyncStatus.RUNNING);
            sleep(200);
        }
        // setlist.fm 행사별 병합처럼 Lease 없이 같은 출처 run 을 시작해도 정상 run 을 닫지 않는다
        Long unfenced = recorder.start("SETLISTFM");
        assertThat(syncRuns.findById(ownerRun).orElseThrow().getStatus()).isEqualTo(SyncStatus.RUNNING);
        recorder.finish(unfenced, SyncStatus.OK, "병합");

        assertThat(ownerCall.get(20, TimeUnit.SECONDS).ok()).isTrue();
        assertThat(busy).isPositive();
        assertThat(syncRuns.findById(ownerRun).orElseThrow().getStatus()).isEqualTo(SyncStatus.OK);
    }

    @Test
    @DisplayName("공급원이 다른 동기화(Ticketmaster·KOPIS 둘 다 시작 시각 제공자)가 같은 행사를 동시에 접어도 (event, field) 당 PENDING 은 1건이다 (행사 행 잠금)")
    void concurrent_providers_keep_one_pending_per_event_field() throws Exception {
        LocalDateTime at19 = DATE.atTime(19, 0);
        LocalDateTime at20 = DATE.atTime(20, 0);
        for (int round = 0; round < 20; round++) {
            Long eventId = newEventWithStart("동시 접기 " + round, DATE, at19).getId();
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<?>> calls = new ArrayList<>();
            for (Provider provider : List.of(Provider.TICKETMASTER, Provider.KOPIS)) {
                calls.add(pool.submit(() -> {
                    barrier.await();
                    return applySources.apply(eventId, Map.of(provider,
                            snapshot(provider + "-" + eventId, DATE.toString(), null, at20, DATE, null)));
                }));
            }
            for (Future<?> call : calls) {
                call.get(30, TimeUnit.SECONDS);
            }
            assertThat(changeRepository.findAllByEventIdAndStatus(eventId, SourceChangeStatus.PENDING))
                    .as("round=%d", round).singleElement()
                    .satisfies(c -> assertThat(c.getField()).isEqualTo(SourceChangeField.STARTS_AT));
        }
    }

    private long maxRunId() {
        Long id = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM sync_runs", Long.class);
        return id == null ? 0 : id;
    }

    private Long onlyRunId() {
        return runs().stream().map(SyncRun::getId).reduce((a, b) -> {
            throw new AssertionError("run 이 하나여야 한다");
        }).orElseThrow();
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
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
