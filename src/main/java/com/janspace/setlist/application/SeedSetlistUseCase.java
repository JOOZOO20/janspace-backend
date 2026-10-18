package com.janspace.setlist.application;

import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import com.janspace.setlist.domain.RevisionSource;
import com.janspace.setlist.domain.SetlistPage;
import com.janspace.setlist.domain.SetlistPageRepository;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 공연 전 시드: 페이지가 비어 있고(리비전 없음) 그 전에 병합된 셋리가 있는 행사가 있으면 그 셋리를 SETLISTFM_SEED 로 넣는다.
 * 없으면 빈 페이지 그대로. 시드 항목은 이 행사의 setlist.fm 이 아니므로 in_setlistfm = false.
 * 스케줄은 단일 인스턴스 전제다(분산 잠금 없음). 여러 인스턴스가 같이 돌아도 페이지 행 잠금과 "잠근 뒤 version 확인" 때문에 중복 시드는 생기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeedSetlistUseCase {

    static final int DAYS_AHEAD = 3;

    private final SetlistWriter writer;
    private final SetlistPageRepository pages;
    private final SetlistQuery setlistQuery;
    private final EventQuery eventQuery;
    private final TransactionTemplate transactionTemplate;

    // 오늘부터 3일 안의 행사 전부. 시드한 건수를 돌려준다.
    // 행사마다 별도 트랜잭션: 한 행사의 실패가 같은 날 다른 행사의 시드를 되돌리지 않는다. 병합된 행사 목록은 한 번만 읽는다
    public int seedUpcoming(LocalDate today) {
        List<Long> mergedIds = pages.findAllByMergedAtIsNotNull().stream().map(SetlistPage::getEventId).toList();
        List<EventSnapshot> merged = mergedIds.isEmpty() ? List.of() : eventQuery.findByIds(mergedIds);
        int seeded = 0;
        for (EventSnapshot event : eventQuery.findBetween(today, today.plusDays(DAYS_AHEAD + 1))) {
            try {
                if (Boolean.TRUE.equals(transactionTemplate.execute(status -> seed(event, merged)))) {
                    seeded++;
                }
            } catch (RuntimeException e) {
                log.error("[셋리 시드 실패] eventId={}", event.id(), e);
            }
        }
        log.info("[셋리 시드] today={}, seeded={}", today, seeded);
        return seeded;
    }

    private boolean seed(EventSnapshot event, List<EventSnapshot> merged) {
        Optional<Long> previous = previousMergedEvent(merged, event);
        if (previous.isEmpty()) {
            return false;
        }
        List<SetlistEntry> entries = setlistQuery.itemsOf(previous.get()).stream()
                .map(e -> new SetlistEntry(e.songId(), e.position(), false)).toList();
        if (entries.isEmpty()) {
            return false;
        }
        // 비어 있는지는 잠근 뒤에 본다. 미리 보면 그사이 커밋된 팬 편집을 시드가 덮는다
        SetlistPage page = writer.pageOf(event.id());
        if (page.getVersion() > 0) {
            return false;
        }
        writer.write(page, entries, RevisionSource.SETLISTFM_SEED, null);
        log.info("[셋리 시드 적용] eventId={}, fromEventId={}, 곡수={}", event.id(), previous.get(), entries.size());
        return true;
    }

    // 병합된 행사 중 이 행사보다 앞선 날짜의 가장 최근 행사
    private static Optional<Long> previousMergedEvent(List<EventSnapshot> merged, EventSnapshot self) {
        return merged.stream()
                .filter(e -> !e.id().equals(self.id()) && e.eventDate().isBefore(self.eventDate()))
                .max(Comparator.comparing(EventSnapshot::eventDate).thenComparing(EventSnapshot::id))
                .map(EventSnapshot::id);
    }
}
