package com.stagelog.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.ops.application.JobLeases;
import com.stagelog.ops.application.JobLeases.Holder;
import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.ops.application.SyncRunRecorder;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyncRunnerTest {

    // jan.sync.lease.enabled=false: 이전 CAS 동작
    static final SyncLeaseProperties CAS = new SyncLeaseProperties(false, 120, 30);

    @Test
    @DisplayName("Lease 를 다른 소유자가 쥐고 있으면 sync_runs 를 남기지 않고 busy 결과에 소유자·만료 시각을 담는다")
    void leaseHeldElsewhereReturnsBusyWithHolder() {
        SyncRunRecorder recorder = mock(SyncRunRecorder.class);
        JobLeases leases = mock(JobLeases.class);
        Holder holder = new Holder("other-host:1:uuid", Instant.parse("2026-11-05T12:02:00Z"));
        when(leases.tryAcquire(eq("sync:KOPIS"), anyString(), eq(120L))).thenReturn(Optional.empty());
        when(leases.holder("sync:KOPIS")).thenReturn(Optional.of(holder));
        SyncRunner runner = new SyncRunner(recorder, mock(OperatorAlert.class), leases, new SyncLeaseProperties(true, 120, 30));

        SyncResult result = runner.run("KOPIS", () -> "실행되면 안 됨");

        assertThat(result.busy()).isTrue();
        assertThat(result.holder()).isEqualTo(holder);
        verify(recorder, never()).start(anyString(), any());
    }

    @Test
    @DisplayName("동기화가 실행 중이면 다른 동기화(관리자 수동 실행 등)는 sync_runs 를 남기지 않고 건너뛴다. 끝나면 다시 실행된다")
    void skipsWhileAnotherSyncIsRunning() {
        SyncRunRecorder recorder = mock(SyncRunRecorder.class);
        SyncRunner runner = new SyncRunner(recorder, mock(OperatorAlert.class), null, CAS);
        AtomicReference<SyncResult> nested = new AtomicReference<>();

        SyncResult outer = runner.run("NAVER", () -> {
            nested.set(runner.run("KOPIS", () -> "실행되면 안 됨"));
            return "네이버 완료";
        });

        assertThat(outer.summary()).isEqualTo("네이버 완료");
        assertThat(nested.get().ok()).isTrue();
        assertThat(nested.get().summary()).contains("실행 중").contains("NAVER");
        verify(recorder, times(1)).start(anyString());

        assertThat(runner.run("KOPIS", () -> "KOPIS 완료").summary()).isEqualTo("KOPIS 완료");
    }

    @Test
    @DisplayName("본문 예외는 FAILED 결과로 바꾸고 다음 실행을 막지 않는다. 요약의 키 값은 가린다")
    void failureReleasesGuardAndMasksKeys() {
        SyncRunner runner = new SyncRunner(mock(SyncRunRecorder.class), mock(OperatorAlert.class), null, CAS);

        SyncResult failed = runner.run("KOPIS", () -> {
            throw new IllegalStateException("GET /pblprfr?service=SECRET123&stdate=20261001");
        });

        assertThat(failed.failed()).isTrue();
        assertThat(failed.summary()).contains("service=***").doesNotContain("SECRET123");
        assertThat(runner.run("KOPIS", () -> "재실행").ok()).isTrue();
    }
}
