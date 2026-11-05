package com.stagelog.ops.application;

import com.stagelog.ops.domain.SyncItemFailureRepository;
import com.stagelog.ops.domain.SyncItemFailureRepository.RunFailureCount;
import com.stagelog.ops.domain.SyncRun;
import com.stagelog.ops.domain.SyncRunRepository;
import com.stagelog.ops.domain.SyncSource;
import com.stagelog.ops.domain.SyncStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
    private final SyncItemFailureRepository itemFailures;

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

    /** 다른 기능에 공개하는 실행 요약. ops.domain 의 엔티티·enum 대신 이름 문자열을 쓴다. */
    public record SyncRunSummary(Long id, String source, String status, Instant startedAt, Instant finishedAt, String summary) {
    }

    // 출처의 가장 최근 실행 (성공·실패·진행 중 무관). 진단 API 가 "마지막 실행 결과"로 보여준다
    @Transactional(readOnly = true)
    public Optional<SyncRunSummary> latestOf(String source) {
        return syncRunRepository.findFirstBySourceOrderByStartedAtDescIdDesc(SyncSource.valueOf(source))
                .map(run -> new SyncRunSummary(run.getId(), run.getSource().name(), run.getStatus().name(), run.getStartedAt(),
                        run.getFinishedAt(), run.getSummary()));
    }

    /**
     * 행사 하나의 가장 최근 종료된 실행. 행사별로 도는 실행(YOUTUBE 재생목록 동기화)은 요약에 "eventId=<id> " 를 남기므로 그 문자열로 찾는다.
     * 진행 중(RUNNING) 실행은 요약이 없어 잡히지 않는다.
     */
    // ponytail: 요약 문자열 부분 일치(인덱스 없음). 행사별 실행이 많아지면 sync_runs 에 event_id 컬럼을 둔다
    @Transactional(readOnly = true)
    public Optional<SyncRunSummary> latestForEvent(String source, Long eventId) {
        return syncRunRepository.findFirstBySourceAndSummaryContainingOrderByStartedAtDescIdDesc(SyncSource.valueOf(source),
                        "eventId=" + eventId + " ")
                .map(run -> new SyncRunSummary(run.getId(), run.getSource().name(), run.getStatus().name(), run.getStartedAt(),
                        run.getFinishedAt(), run.getSummary()));
    }

    // 실행별 실패 항목 수(sync_item_failures). 실패가 없는 실행은 맵에 없다
    @Transactional(readOnly = true)
    public Map<Long, Long> failureCounts(Collection<Long> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        return itemFailures.countByRunIds(runIds).stream()
                .collect(Collectors.toMap(RunFailureCount::getSyncRunId, RunFailureCount::getFailures));
    }
}
