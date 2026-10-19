package com.stagelog.ops.application;

import com.stagelog.ops.domain.SyncRun;
import com.stagelog.ops.domain.SyncRunRepository;
import com.stagelog.ops.domain.SyncSource;
import com.stagelog.ops.domain.SyncStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동기화 이력 조회. 관리자 화면용이며 최신순 상위 N 건만 내린다.
 */
@Service
@RequiredArgsConstructor
public class SyncRunQuery {

    private final SyncRunRepository syncRunRepository;

    @Transactional(readOnly = true)
    public List<SyncRun> latest(int size) {
        return syncRunRepository.findAllByOrderByStartedAtDescIdDesc(Limit.of(size));
    }

    // 마지막으로 성공(OK)한 실행의 시작 시각. 증분 조회(KOPIS afterdate)의 기준
    @Transactional(readOnly = true)
    public Optional<Instant> lastSucceededAt(String source) {
        return syncRunRepository.findFirstBySourceAndStatusOrderByStartedAtDescIdDesc(SyncSource.valueOf(source), SyncStatus.OK)
                .map(SyncRun::getStartedAt);
    }
}
