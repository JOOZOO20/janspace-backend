package com.stagelog.event.application.sync;

import com.stagelog.event.application.EventQuery;
import com.stagelog.event.application.port.SetlistFmEventPort;
import com.stagelog.event.application.sync.SourceLinker.Kind;
import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.Provider;
import com.stagelog.ops.application.OperatorAlert;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * setlist.fm 행사 동기화. 최신순 페이지를 훑어 2년 이내 셋리마다 같은 날짜(·국가)의 행사에 공연장·도시를 붙이고,
 * 없으면 발견 행으로 남긴다. 곡 병합은 setlist 기능의 몫이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SetlistFmEventSync {

    static final int MAX_PAGES = 20;
    static final int YEARS_BACK = 2;

    private final SetlistFmEventPort port;
    private final SourceLinker linker;
    private final SyncRunner runner;
    private final OperatorAlert operatorAlert;

    public SyncResult sync() {
        return sync(LocalDate.now(EventQuery.LIST_ZONE));
    }

    public SyncResult sync(LocalDate today) {
        if (!port.isEnabled()) {
            return SyncResult.skipped(Provider.SETLISTFM.name(), "SETLISTFM_API_KEY 또는 MBID 미설정, 건너뜀");
        }
        return runner.run(Provider.SETLISTFM.name(), () -> doSync(today.minusYears(YEARS_BACK)));
    }

    private String doSync(LocalDate cutoff) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        int seen = 0;
        int pages = 0;
        int failed = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<SourceSnapshot> items = port.page(page);
            if (items.isEmpty()) {
                break;
            }
            pages++;
            boolean allOld = true;
            for (SourceSnapshot snapshot : items) {
                if (snapshot.eventDate().isBefore(cutoff)) {
                    continue;
                }
                allOld = false;
                seen++;
                // 항목 하나의 처리 실패는 건너뛰고 실행은 끝에서 FAILED 로 기록한다. 페이지 호출 실패는 그대로 실행 실패
                try {
                    counts.merge(linker.link(Provider.SETLISTFM, snapshot).kind(), 1, Integer::sum);
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("[setlist.fm 항목 실패] setlistId={}, key={}", snapshot.externalId(), snapshot.occurrenceKey(), e);
                }
            }
            if (allOld) {
                break;
            }
        }
        String summary = "setlist.fm " + pages + "페이지, " + cutoff + " 이후 셋리 " + seen + "건: 연결 " + counts.getOrDefault(Kind.LINKED, 0)
                + ", 갱신 " + counts.getOrDefault(Kind.REFRESHED, 0) + ", 이동 제안 " + counts.getOrDefault(Kind.MOVE_PROPOSED, 0)
                + ", 발견 " + counts.getOrDefault(Kind.DISCOVERED, 0);
        if (failed > 0) {
            throw new IllegalStateException(summary + ", 항목 실패 " + failed);
        }
        log.info("[setlist.fm 행사 동기화] summary={}", summary);
        if (counts.getOrDefault(Kind.LINKED, 0) > 0 || counts.getOrDefault(Kind.DISCOVERED, 0) > 0) {
            operatorAlert.send("[setlist.fm 행사 동기화] " + summary);
        }
        return summary;
    }
}
