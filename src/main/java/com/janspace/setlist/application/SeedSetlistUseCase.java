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
import org.springframework.transaction.annotation.Transactional;

/**
 * 공연 전 시드: 페이지가 비어 있고(리비전 없음) 그 전에 병합된 셋리가 있는 행사가 있으면 그 셋리를 SETLISTFM_SEED 로 넣는다.
 * 없으면 빈 페이지 그대로. 시드 항목은 이 행사의 setlist.fm 이 아니므로 in_setlistfm = false.
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

    // 오늘부터 3일 안의 행사 전부. 시드한 건수를 돌려준다
    @Transactional
    public int seedUpcoming(LocalDate today) {
        int seeded = 0;
        for (EventSnapshot event : eventQuery.findBetween(today, today.plusDays(DAYS_AHEAD + 1))) {
            if (seed(event.id())) {
                seeded++;
            }
        }
        log.info("[셋리 시드] today={}, seeded={}", today, seeded);
        return seeded;
    }

    @Transactional
    public boolean seed(Long eventId) {
        EventSnapshot event = eventQuery.find(eventId).orElse(null);
        if (event == null) {
            return false;
        }
        Optional<Long> previous = previousMergedEvent(event.eventDate(), eventId);
        if (previous.isEmpty()) {
            return false;
        }
        List<SetlistEntry> entries = setlistQuery.itemsOf(previous.get()).stream()
                .map(e -> new SetlistEntry(e.songId(), e.position(), false)).toList();
        if (entries.isEmpty()) {
            return false;
        }
        // 비어 있는지는 잠근 뒤에 본다. 미리 보면 그사이 커밋된 팬 편집을 시드가 덮는다
        SetlistPage page = writer.pageOf(eventId);
        if (page.getVersion() > 0) {
            return false;
        }
        writer.write(page, entries, RevisionSource.SETLISTFM_SEED, null);
        log.info("[셋리 시드 적용] eventId={}, fromEventId={}, 곡수={}", eventId, previous.get(), entries.size());
        return true;
    }

    // 병합된 페이지 중 이 행사보다 앞선 날짜의 가장 최근 행사
    private Optional<Long> previousMergedEvent(LocalDate before, Long selfId) {
        List<Long> mergedIds = pages.findAllByMergedAtIsNotNull().stream().map(SetlistPage::getEventId).filter(id -> !id.equals(selfId)).toList();
        if (mergedIds.isEmpty()) {
            return Optional.empty();
        }
        return eventQuery.findByIds(mergedIds).stream()
                .filter(e -> e.eventDate().isBefore(before))
                .max(Comparator.comparing(EventSnapshot::eventDate).thenComparing(EventSnapshot::id))
                .map(EventSnapshot::id);
    }
}
