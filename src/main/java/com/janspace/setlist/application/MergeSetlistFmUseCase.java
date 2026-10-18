package com.janspace.setlist.application;

import com.janspace.catalog.application.CatalogQuery;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import com.janspace.ops.application.OperatorAlert;
import com.janspace.ops.application.SyncRunRecorder;
import com.janspace.setlist.application.event.SetlistMerged;
import com.janspace.setlist.application.port.FmSetlist;
import com.janspace.setlist.application.port.SetlistFmSetlistPort;
import com.janspace.setlist.domain.MergePolicy;
import com.janspace.setlist.domain.RevisionSource;
import com.janspace.setlist.domain.SetlistPage;
import com.janspace.setlist.domain.SetlistPageRepository;
import com.janspace.setlist.domain.UnmatchedName;
import com.janspace.setlist.domain.UnmatchedNameRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * setlist.fm 병합: 곡명 -> 곡 ID(별칭·제목 정규화), 미매칭은 큐에, 맞는 곡이 하나라도 있으면 merged_at, MergePolicy 로 합쳐 차이가 있을 때만
 * SETLISTFM_MERGE 리비전 + 운영자 알림(커밋 뒤) + SetlistMerged. 실행마다 sync_runs(SETLISTFM) 에 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MergeSetlistFmUseCase {

    public static final String PROVIDER = "SETLISTFM";
    static final int RECENT_DAYS = 14;

    private final SetlistWriter writer;
    private final SetlistPageRepository pages;
    private final UnmatchedNameRepository unmatched;
    private final SetlistFmSetlistPort setlistFm;
    private final CatalogQuery catalogQuery;
    private final EventQuery eventQuery;
    private final OperatorAlert operatorAlert;
    private final SyncRunRecorder syncRuns;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transactionTemplate;

    // 스케줄: setlist.fm 이 연결된 행사 중 아직 병합 안 됐거나 최근 14일 안의 행사. 변경된 행사 수를 돌려준다.
    // 행사마다 별도 트랜잭션 (자기 호출은 @Transactional 프록시를 타지 않으므로 TransactionTemplate 으로 감싼다)
    public int mergeDue(LocalDate today) {
        int changed = 0;
        for (EventSnapshot event : eventQuery.findLinkedTo(PROVIDER)) {
            boolean merged = pages.findById(event.id()).map(p -> p.getMergedAt() != null).orElse(false);
            if (merged && event.eventDate().isBefore(today.minusDays(RECENT_DAYS))) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(transactionTemplate.execute(status -> merge(event.id())))) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.error("[셋리 병합 실패] eventId={}", event.id(), e);
            }
        }
        log.info("[셋리 병합 스케줄] today={}, changed={}", today, changed);
        return changed;
    }

    // 변경이 있었으면 true. setlist.fm 이 연결되지 않았거나 셋리가 아직 없으면 false
    @Transactional
    public boolean merge(Long eventId) {
        EventSnapshot event = eventQuery.find(eventId).orElse(null);
        String setlistId = event == null ? null : event.externalIds().get(PROVIDER);
        if (setlistId == null) {
            log.info("[셋리 병합 건너뜀] setlist.fm 미연결 eventId={}", eventId);
            return false;
        }
        Long runId = syncRuns.start(PROVIDER);
        try {
            // 외부 호출: setlist.fm
            Optional<FmSetlist> fetched = setlistFm.fetch(setlistId);
            if (fetched.isEmpty()) {
                syncRuns.succeed(runId, "eventId=" + eventId + " setlist.fm 셋리 없음 id=" + setlistId);
                return false;
            }
            boolean changed = apply(eventId, fetched.get());
            syncRuns.succeed(runId, "eventId=" + eventId + " changed=" + changed);
            return changed;
        } catch (RuntimeException e) {
            syncRuns.fail(runId, "eventId=" + eventId + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    private boolean apply(Long eventId, FmSetlist fm) {
        SetlistPage page = writer.pageOf(eventId);
        // 1. 곡명 -> 곡 ID. 미매칭 큐를 이번 결과에 맞춘다: 이번 미매칭 목록에 없는 행(별칭이 생겨 이제 맞는 이름 등)은 지우고,
        //    안 맞는 이름은 넣는다(같은 이름 중복 없이). 관리자가 곡에 연결하면 행이 지워지는 것과 같은 결과
        List<Long> fmSongIds = new ArrayList<>();
        List<String> unmatchedNames = new ArrayList<>();
        for (String name : fm.songNames()) {
            catalogQuery.matchSetlistFmName(name).ifPresentOrElse(fmSongIds::add, () -> unmatchedNames.add(name));
        }
        long resolved = unmatched.deleteByEventIdAndRawNameNotIn(eventId, unmatchedNames);
        if (resolved > 0) {
            log.info("[미매칭 곡명 해소] eventId={}, resolved={}", eventId, resolved);
        }
        unmatchedNames.stream().distinct()
                .filter(name -> !unmatched.existsByEventIdAndRawName(eventId, name))
                .forEach(name -> unmatched.save(new UnmatchedName(eventId, name)));
        // 2. 맞는 곡이 하나라도 있으면 변경이 없어도 병합된 것으로 찍는다(mergeDue 의 매일 재조회·시드 후보 제외 방지).
        //    전부 미매칭이면 시드 원본이 될 수 없어 찍지 않는다
        if (!fmSongIds.isEmpty()) {
            page.markMerged(Instant.now());
        }
        // 3. 병합
        List<SetlistEntry> before = writer.currentEntries(eventId);
        MergePolicy.Result result = MergePolicy.merge(
                before.stream().map(e -> new MergePolicy.Entry(e.songId(), e.inSetlistFm())).toList(), fmSongIds);
        if (!result.changed()) {
            log.info("[셋리 병합 변경 없음] eventId={}, unmatched={}", eventId, unmatchedNames.size());
            return false;
        }
        // 4. 리비전 + 알림 + 이벤트
        List<SetlistEntry> after = new ArrayList<>();
        for (int i = 0; i < result.entries().size(); i++) {
            MergePolicy.Entry e = result.entries().get(i);
            after.add(new SetlistEntry(e.songId(), i + 1, e.inSetlistFm()));
        }
        int version = writer.write(page, after, RevisionSource.SETLISTFM_MERGE, null);
        // 알림은 커밋 뒤. 롤백된 병합을 알리지 않고, 디스코드 호출 동안 페이지 행 잠금을 잡고 있지 않는다
        String alert = diff(eventId, version, before, after, unmatchedNames);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                operatorAlert.send(alert);
            }
        });
        publisher.publishEvent(new SetlistMerged(eventId));
        return true;
    }

    private static String diff(Long eventId, int version, List<SetlistEntry> before, List<SetlistEntry> after, List<String> unmatchedNames) {
        Set<Long> was = before.stream().map(SetlistEntry::songId).collect(Collectors.toSet());
        Set<Long> now = after.stream().map(SetlistEntry::songId).collect(Collectors.toSet());
        String added = now.stream().filter(id -> !was.contains(id)).map(String::valueOf).collect(Collectors.joining(","));
        String wikiOnly = after.stream().filter(e -> !e.inSetlistFm()).map(e -> String.valueOf(e.songId())).collect(Collectors.joining(","));
        return "[셋리 병합 적용] eventId=" + eventId + " v" + version
                + " 추가 songId=[" + added + "] 위키에만 있음 songId=[" + wikiOnly + "] 미매칭=" + unmatchedNames
                + " — 이상하면 POST /api/v1/events/" + eventId + "/setlist/revert {version: " + (version - 1) + "}";
    }
}
