package com.janspace.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.janspace.event.application.sync.SyncRunner.SyncResult;
import com.janspace.ops.application.OperatorAlert;
import com.janspace.ops.application.SyncRunRecorder;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyncRunnerTest {

    @Test
    @DisplayName("동기화가 실행 중이면 다른 동기화(관리자 수동 실행 등)는 sync_runs 를 남기지 않고 건너뛴다. 끝나면 다시 실행된다")
    void skipsWhileAnotherSyncIsRunning() {
        SyncRunRecorder recorder = mock(SyncRunRecorder.class);
        SyncRunner runner = new SyncRunner(recorder, mock(OperatorAlert.class));
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
        SyncRunner runner = new SyncRunner(mock(SyncRunRecorder.class), mock(OperatorAlert.class));

        SyncResult failed = runner.run("KOPIS", () -> {
            throw new IllegalStateException("GET /pblprfr?service=SECRET123&stdate=20261001");
        });

        assertThat(failed.failed()).isTrue();
        assertThat(failed.summary()).contains("service=***").doesNotContain("SECRET123");
        assertThat(runner.run("KOPIS", () -> "재실행").ok()).isTrue();
    }
}
