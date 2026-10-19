package com.stagelog.ops.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.ops.domain.SyncRun;
import com.stagelog.ops.domain.SyncRunRepository;
import com.stagelog.ops.domain.SyncStatus;
import com.stagelog.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SyncRunRecorderIntegrationTest extends IntegrationTest {

    @Autowired
    SyncRunRecorder recorder;

    @Autowired
    SyncRunRepository repository;

    @Autowired
    PlatformTransactionManager transactionManager;

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
    @DisplayName("JVM 이 죽어 RUNNING 으로 남은 실행은 같은 출처의 다음 start 때 FAILED 로 닫히고, 다른 출처는 건드리지 않는다")
    void next_start_closes_stale_running_of_same_source() {
        Long stale = recorder.start("TICKETMASTER");
        Long otherSource = recorder.start("KOPIS");

        Long next = recorder.start("TICKETMASTER");

        SyncRun closed = repository.findById(stale).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(closed.getFinishedAt()).isNotNull();
        assertThat(closed.getSummary()).isEqualTo(SyncRunRecorder.STALE_SUMMARY);
        assertThat(repository.findById(next).orElseThrow().getStatus()).isEqualTo(SyncStatus.RUNNING);
        assertThat(repository.findById(otherSource).orElseThrow().getStatus()).isEqualTo(SyncStatus.RUNNING);

        recorder.finish(next, SyncStatus.OK, "ok");
        recorder.finish(otherSource, SyncStatus.OK, "ok");
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
