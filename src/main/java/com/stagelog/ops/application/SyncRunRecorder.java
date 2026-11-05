package com.stagelog.ops.application;

import com.stagelog.ops.domain.JobLeaseRepository;
import com.stagelog.ops.domain.SyncItemFailure;
import com.stagelog.ops.domain.SyncItemFailureRepository;
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
    public static final String LEASE_EXPIRED = "LEASE_EXPIRED";
    // ponytail: Lease 가 없는 run(YOUTUBE 재생목록, setlist.fm 행사별 병합)은 소유자 생존을 알 수 없어 시작 후 경과 시간으로만 판정한다.
    //           이보다 오래 도는 정상 실행은 오종료되고, 죽은 실행은 이만큼 RUNNING 으로 남는다. 문제되면 이 run 들도 Lease 를 거치게 한다
    static final long UNFENCED_STALE_MINUTES = 30;

    private final SyncRunRepository syncRunRepository;
    private final JobLeaseRepository jobLeaseRepository;
    private final SyncItemFailureRepository itemFailureRepository;

    // Lease 없이 기록하는 실행. 다른 기능은 ops.domain 의 enum 을 참조할 수 없으므로(ArchUnit) 출처 이름 문자열로 부른다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(String sourceName) {
        return start(sourceName, null);
    }

    // fencingToken = 이 실행이 획득한 Lease token (Lease 없이 실행하면 null)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(String sourceName, Long fencingToken) {
        SyncSource source = SyncSource.valueOf(sourceName);
        Instant now = Instant.now();
        // 1. 소유자가 종료 기록 없이 사라진 같은 출처의 RUNNING 만 FAILED 로 닫는다. 다른 서버에서 정상 실행 중인 run 은 건드리지 않는다
        //    a. Lease run: 그 Lease 를 이제 아무도 쥐고 있지 않음 (DB 시계 기준 만료, 또는 다른 소유자가 획득)
        for (SyncRun stale : syncRunRepository.findLeaseExpiredRunning(source.name(), JobLeases.syncKey(source.name()))) {
            stale.finish(SyncStatus.FAILED, LEASE_EXPIRED, now);
            log.warn("[동기화 미종료 실행 정리 - Lease 만료] source={}, runId={}, token={}, startedAt={}", source, stale.getId(),
                    stale.getFencingToken(), stale.getStartedAt());
        }
        //    b. Lease 없는 run: 시작 후 30분 경과
        for (SyncRun stale : syncRunRepository.findUnfencedRunningStartedBefore(source.name(), UNFENCED_STALE_MINUTES)) {
            stale.finish(SyncStatus.FAILED, STALE_SUMMARY, now);
            log.warn("[동기화 미종료 실행 정리] source={}, runId={}, startedAt={}", source, stale.getId(), stale.getStartedAt());
        }

        // 2. 새 실행
        SyncRun run = syncRunRepository.save(SyncRun.start(source, now, fencingToken));
        log.info("[동기화 시작] source={}, runId={}, token={}", source, run.getId(), fencingToken);
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
    public boolean partial(Long runId, String summary) {
        return finish(runId, SyncStatus.PARTIAL, summary);
    }

    // 실행 안 항목 하나의 실패 (KOPIS 건별 격리). 실행 이력과 같이 호출자 트랜잭션과 무관하게 남는다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordItemFailure(Long runId, String sourceName, String externalId, RuntimeException error) {
        itemFailureRepository.save(new SyncItemFailure(runId, SyncSource.valueOf(sourceName), externalId,
                error.getClass().getSimpleName(), error.getMessage()));
        log.warn("[동기화 항목 실패 기록] source={}, runId={}, externalId={}, error={}", sourceName, runId, externalId,
                error.getClass().getSimpleName());
    }

    // 외부 API Quota 부족으로 시작하지 않고 미룸. 호출자가 다음 리셋 뒤 다시 실행한다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void defer(Long runId, String summary) {
        finish(runId, SyncStatus.DEFERRED, summary);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(Long runId, boolean ok, String summary) {
        return finish(runId, ok ? SyncStatus.OK : SyncStatus.FAILED, summary);
    }

    // false = 반영하지 않음. Lease run 은 Lease 의 현재 token 이 run 의 token 과 같을 때만 반영한다
    // (Lease 를 잃은 뒤 복귀한 이전 소유자가 새 소유자 시점의 기록을 덮지 못하게 한다)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finish(Long runId, SyncStatus status, String summary) {
        SyncRun run = syncRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 동기화 실행입니다. runId=" + runId));
        if (run.getFencingToken() != null) {
            // Lease 행을 잠가 비교와 기록 사이에 다른 서버가 획득하지 못하게 한다
            Long current = jobLeaseRepository.findTokenForUpdate(JobLeases.syncKey(run.getSource().name())).orElse(null);
            if (!run.getFencingToken().equals(current)) {
                log.warn("[동기화 종료 기록 거부 - fencing token 불일치] source={}, runId={}, runToken={}, currentToken={}, status={}",
                        run.getSource(), runId, run.getFencingToken(), current, status);
                return false;
            }
        }
        run.finish(status, summary, Instant.now());
        log.info("[동기화 종료] source={}, runId={}, status={}", run.getSource(), runId, status);
        return true;
    }
}
