package com.janspace.setlist.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.application.EventQuery;
import com.janspace.setlist.application.event.SetlistChanged;
import com.janspace.setlist.domain.RevisionSource;
import com.janspace.setlist.domain.SetlistItem;
import com.janspace.setlist.domain.SetlistItemRepository;
import com.janspace.setlist.domain.SetlistPage;
import com.janspace.setlist.domain.SetlistPageRepository;
import com.janspace.setlist.domain.SetlistRevision;
import com.janspace.setlist.domain.SetlistRevisionRepository;
import com.janspace.setlist.domain.SnapshotCodec;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 유스케이스들이 공유하는 쓰기 단계: 페이지 확보 -> 항목 교체 -> 리비전 기록 -> 버전 증가 -> SetlistChanged 발행.
 * 호출자의 트랜잭션 안에서 실행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SetlistWriter {

    private final SetlistPageRepository pages;
    private final SetlistItemRepository items;
    private final SetlistRevisionRepository revisions;
    private final EventQuery eventQuery;
    private final ApplicationEventPublisher publisher;

    // 없는 행사는 404. 페이지는 첫 쓰기 때 만든다
    SetlistPage pageOf(Long eventId) {
        return pages.findById(eventId).orElseGet(() -> {
            if (eventQuery.find(eventId).isEmpty()) {
                throw new NotFoundException(ErrorCode.EVENT_NOT_FOUND);
            }
            return pages.save(new SetlistPage(eventId));
        });
    }

    List<SetlistEntry> currentEntries(Long eventId) {
        return items.findAllByEventIdOrderByPosition(eventId).stream().map(SetlistQuery::entry).toList();
    }

    // 항목을 통째로 바꾸고 리비전을 남긴다. 새 버전 번호를 돌려준다
    int write(SetlistPage page, List<SetlistEntry> entries, RevisionSource source, Long editorUserId) {
        Long eventId = page.getEventId();
        items.deleteAllByEventId(eventId);
        items.saveAll(entries.stream().map(e -> new SetlistItem(eventId, e.songId(), e.position(), e.inSetlistFm())).toList());
        int version = page.nextVersion();
        String snapshot = SnapshotCodec.encode(entries.stream()
                .map(e -> new SnapshotCodec.Entry(e.songId(), e.position(), e.inSetlistFm())).toList());
        revisions.save(new SetlistRevision(eventId, version, editorUserId, source, snapshot));
        publisher.publishEvent(new SetlistChanged(eventId));
        log.info("[셋리 리비전 기록] eventId={}, version={}, source={}, 곡수={}", eventId, version, source, entries.size());
        return version;
    }
}
