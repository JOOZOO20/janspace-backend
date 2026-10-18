package com.janspace.ops.application;

import com.janspace.ops.domain.SyncRun;
import com.janspace.ops.domain.SyncRunRepository;
import com.janspace.ops.domain.SyncSource;
import com.janspace.ops.domain.SyncStatus;
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

    private final SyncRunRepository syncRunRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(SyncSource source) {
        SyncRun run = syncRunRepository.save(SyncRun.start(source, Instant.now()));
        log.info("[동기화 시작] source={}, runId={}", source, run.getId());
        return run.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long runId, SyncStatus status, String summary) {
        SyncRun run = syncRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 동기화 실행입니다. runId=" + runId));
        run.finish(status, summary, Instant.now());
        log.info("[동기화 종료] source={}, runId={}, status={}", run.getSource(), runId, status);
    }
}
