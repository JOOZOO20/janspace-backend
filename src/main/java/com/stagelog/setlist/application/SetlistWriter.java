package com.stagelog.setlist.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.event.application.EventQuery;
import com.stagelog.setlist.application.event.SetlistChanged;
import com.stagelog.setlist.domain.RevisionSource;
import com.stagelog.setlist.domain.SetlistItem;
import com.stagelog.setlist.domain.SetlistItemRepository;
import com.stagelog.setlist.domain.SetlistPage;
import com.stagelog.setlist.domain.SetlistPageRepository;
import com.stagelog.setlist.domain.SetlistRevision;
import com.stagelog.setlist.domain.SetlistRevisionRepository;
import com.stagelog.setlist.domain.SnapshotCodec;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 유스케이스들이 공유하는 쓰기 단계: 페이지 확보 -> 항목 교체 -> 리비전 기록 -> 버전 증가 -> SetlistChanged 발행.
 * 호출자의 트랜잭션 안에서 실행된다. pageOf 가 페이지 행을 잠가 같은 행사의 쓰기(편집·되돌리기·병합·시드·순서 확정)를 줄 세운다.
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

    // 없는 행사는 404. 페이지는 첫 쓰기 때 만든다. 돌려준 페이지 행은 호출자 트랜잭션이 끝날 때까지 잠겨 있다 (SELECT ... FOR UPDATE).
    // 없는 행에 FOR UPDATE 를 먼저 걸면 InnoDB 가 gap 잠금을 잡아, 첫 쓰기 두 건이 서로의 INSERT 를 막고 교착된다.
    // 그래서 없으면 INSERT ... ON DUPLICATE KEY UPDATE 로 먼저 만들고 그다음 잠근다.
    // 같은 트랜잭션에서 이 페이지를 미리 읽어 두면 잠금 조회가 영속성 컨텍스트의 옛 버전을 돌려주므로, 쓰기 경로는 페이지를 이것으로만 읽는다
    SetlistPage pageOf(Long eventId) {
        if (!pages.existsById(eventId)) {
            if (eventQuery.find(eventId).isEmpty()) {
                throw new NotFoundException(ErrorCode.EVENT_NOT_FOUND);
            }
            pages.insertIfAbsent(eventId);
        }
        return pages.findByIdForUpdate(eventId).orElseThrow();
    }

    // pageOf 로 잠근 뒤의 현재 항목. 반복 읽기 스냅샷은 잠금을 기다리기 전 시점일 수 있어 잠금 읽기로 최신 커밋 값을 읽는다
    List<SetlistEntry> currentEntries(Long eventId) {
        return items.findAllForUpdate(eventId).stream().map(SetlistQuery::entry).toList();
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
