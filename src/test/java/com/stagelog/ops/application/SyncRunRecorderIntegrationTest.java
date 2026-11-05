package com.stagelog.ops.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.ops.domain.SyncRun;
import com.stagelog.ops.domain.SyncRunRepository;
import com.stagelog.ops.domain.SyncStatus;
import com.stagelog.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SyncRunRecorderIntegrationTest extends IntegrationTest {

    @Autowired
    SyncRunRecorder recorder;

    @Autowired
    SyncRunRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JobLeases leases;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("start 는 RUNNING 행을, finish 는 상태·종료 시각·요약을 남긴다")
    void start_then_finish() {
        Long id = recorder.start("KOPIS");

        SyncRun running = repository.findById(id).orElseThrow();
        assertThat(running.getStatus()).isEqualTo(SyncStatus.RUNNING);
        assertThat(running.getStartedAt()).isNotNull();
        assertThat(running.getFinishedAt()).isNull();

        recorder.finish(id, SyncStatus.FAILED, "x".repeat(1500));

        SyncRun finished = repository.findById(id).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(finished.getFinishedAt()).isAfterOrEqualTo(finished.getStartedAt());
        // 컬럼 길이(1000)를 넘는 요약은 잘라서 저장된다
        assertThat(finished.getSummary()).hasSize(1000);
    }

    @Test
    @DisplayName("Lease 없는 run 은 시작 후 30분이 지난 것만 다음 start 때 FAILED 로 닫는다. 진행 중인 run 과 다른 출처는 건드리지 않는다")
    void unfenced_run_is_closed_only_after_30_minutes() {
        Long old = recorder.start("YOUTUBE");
        jdbc.update("UPDATE sync_runs SET started_at = NOW(6) - INTERVAL 31 MINUTE WHERE id = ?", old);
        Long fresh = recorder.start("YOUTUBE");
        Long otherSource = recorder.start("KOPIS");
        jdbc.update("UPDATE sync_runs SET started_at = NOW(6) - INTERVAL 31 MINUTE WHERE id = ?", otherSource);

        Long next = recorder.start("YOUTUBE");

        SyncRun closed = repository.findById(old).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(closed.getFinishedAt()).isNotNull();
        assertThat(closed.getSummary()).isEqualTo(SyncRunRecorder.STALE_SUMMARY);
        assertThat(repository.findById(fresh).orElseThrow().getStatus()).isEqualTo(SyncStatus.RUNNING);
        assertThat(repository.findById(next).orElseThrow().getStatus()).isEqualTo(SyncStatus.RUNNING);
        assertThat(repository.findById(otherSource).orElseThrow().getStatus()).isEqualTo(SyncStatus.RUNNING);

        recorder.finish(fresh, SyncStatus.OK, "ok");
        recorder.finish(next, SyncStatus.OK, "ok");
        recorder.finish(otherSource, SyncStatus.OK, "ok");
    }

    @Test
    @DisplayName("Lease run 은 Lease 가 만료(또는 다른 소유자가 획득)된 것만 LEASE_EXPIRED 로 닫고, 종료 기록은 현재 token 과 같을 때만 반영한다")
    void lease_run_is_closed_only_when_lease_expired_and_finish_is_fenced() {
        String key = JobLeases.syncKey("NAVER");
        jdbc.update("UPDATE job_leases SET owner_id = NULL, expires_at = NULL WHERE lease_key = ?", key);
        long first = leases.tryAcquire(key, "server-a", 120).orElseThrow();
        Long held = recorder.start("NAVER", first);

        // 1. Lease 를 쥐고 있는 동안 다른 run 이 시작돼도 닫히지 않는다
        recorder.finish(recorder.start("NAVER"), SyncStatus.OK, "Lease 없는 같은 출처 run");
        assertThat(repository.findById(held).orElseThrow().getStatus()).isEqualTo(SyncStatus.RUNNING);

        // 2. 만료 후 다른 소유자가 획득하고 시작하면 이전 run 은 LEASE_EXPIRED
        jdbc.update("UPDATE job_leases SET expires_at = NOW(6) - INTERVAL 1 SECOND WHERE lease_key = ?", key);
        long second = leases.tryAcquire(key, "server-b", 120).orElseThrow();
        assertThat(second).isEqualTo(first + 1);
        Long takeover = recorder.start("NAVER", second);
        SyncRun expired = repository.findById(held).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(expired.getSummary()).isEqualTo(SyncRunRecorder.LEASE_EXPIRED);

        // 3. 이전 소유자의 늦은 종료 기록은 거부, 현재 소유자는 반영
        assertThat(recorder.finish(held, true, "늦게 끝남")).isFalse();
        assertThat(repository.findById(held).orElseThrow().getSummary()).isEqualTo(SyncRunRecorder.LEASE_EXPIRED);
        assertThat(recorder.finish(takeover, true, "ok")).isTrue();
        assertThat(repository.findById(takeover).orElseThrow().getStatus()).isEqualTo(SyncStatus.OK);
        assertThat(leases.release(key, "server-a", first)).isFalse();
        assertThat(leases.release(key, "server-b", second)).isTrue();
    }

    @Test
    @DisplayName("바깥 트랜잭션이 롤백돼도 REQUIRES_NEW 로 기록한 이력은 남는다")
    void survives_outer_rollback() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        Long id = outer.execute(status -> {
            Long runId = recorder.start("NAVER");
            recorder.finish(runId, SyncStatus.FAILED, "동기화 트랜잭션 롤백");
            status.setRollbackOnly();
            return runId;
        });

        SyncRun run = repository.findById(id).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(run.getSummary()).isEqualTo("동기화 트랜잭션 롤백");
    }
}
