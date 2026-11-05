package com.stagelog.ops.application;

import com.stagelog.ops.domain.SyncRun;
import com.stagelog.ops.domain.SyncRunRepository;
import com.stagelog.ops.domain.SyncSource;
import com.stagelog.ops.domain.SyncStatus;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동기화 실행 이력 기록 공개 계약. 각 메서드는 REQUIRES_NEW 로 따로 커밋되므로 호출자의 동기화 트랜잭션이 롤백돼도 이력 행은 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncRunRecorder {

    static final String STALE_SUMMARY = "종료 기록 없이 남은 실행. 다음 실행 시작 때 FAILED 로 닫음";

    private final SyncRunRepository syncRunRepository;

    // 다른 기능은 ops.domain 의 enum 을 참조할 수 없으므로(ArchUnit) 출처 이름 문자열로 부른다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(String sourceName) {
        SyncSource source = SyncSource.valueOf(sourceName);
        Instant now = Instant.now();
        // 1. 같은 출처의 RUNNING 은 JVM 이 동기화 중 죽어 종료 기록을 못 남긴 실행으로 보고 FAILED 로 닫는다.
        // ponytail: 같은 출처가 정말 동시에 도는 경우(행사별 YOUTUBE·SETLISTFM 수동 실행)도 잠깐 FAILED 로 보이지만,
        //           그 실행이 끝나면 자기 finish 가 상태·요약을 덮어쓴다. 오탐이 문제되면 시작 후 경과 시간 기준을 더한다
        for (SyncRun stale : syncRunRepository.findAllBySourceAndStatus(source, SyncStatus.RUNNING)) {
            stale.finish(SyncStatus.FAILED, STALE_SUMMARY, now);
            log.warn("[동기화 미종료 실행 정리] source={}, runId={}, startedAt={}", source, stale.getId(), stale.getStartedAt());
        }

        // 2. 새 실행
        SyncRun run = syncRunRepository.save(SyncRun.start(source, now));
        log.info("[동기화 시작] source={}, runId={}", source, run.getId());
        return run.getId();
    }

    // 호출자 트랜잭션의 스냅샷에는 REQUIRES_NEW 로 커밋된 실행 행이 안 보일 수 있어(InnoDB 반복 읽기) 새 트랜잭션에서 종료한다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long runId, String summary) {
        finish(runId, SyncStatus.OK, summary);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long runId, String summary) {
        finish(runId, SyncStatus.FAILED, summary);
    }

    // 일부만 반영하고 끝남 (예: 재생목록 diff 적용 뒤 다시 읽은 항목이 목표와 다름)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void partial(Long runId, String summary) {
        finish(runId, SyncStatus.PARTIAL, summary);
    }

    // 외부 API Quota 부족으로 시작하지 않고 미룸. 호출자가 다음 리셋 뒤 다시 실행한다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void defer(Long runId, String summary) {
        finish(runId, SyncStatus.DEFERRED, summary);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long runId, boolean ok, String summary) {
        finish(runId, ok ? SyncStatus.OK : SyncStatus.FAILED, summary);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long runId, SyncStatus status, String summary) {
        SyncRun run = syncRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 동기화 실행입니다. runId=" + runId));
        run.finish(status, summary, Instant.now());
        log.info("[동기화 종료] source={}, runId={}, status={}", run.getSource(), runId, status);
    }
}
