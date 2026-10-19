package com.stagelog.setlist.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.event.application.EventQuery;
import com.stagelog.setlist.domain.SetlistItem;
import com.stagelog.setlist.domain.SetlistItemRepository;
import com.stagelog.setlist.domain.SetlistPage;
import com.stagelog.setlist.domain.SetlistPageRepository;
import com.stagelog.setlist.domain.SetlistRevisionRepository;
import com.stagelog.setlist.domain.SnapshotCodec;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 셋리 조회. itemsOf/playlistUrl 은 다른 기능에 공개하는 계약, page/history 는 API 용.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SetlistQuery {

    public static final String PLAYLIST_URL_PREFIX = "https://www.youtube.com/playlist?list=";

    private final SetlistPageRepository pages;
    private final SetlistItemRepository items;
    private final SetlistRevisionRepository revisions;
    private final EventQuery eventQuery;

    public List<SetlistEntry> itemsOf(Long eventId) {
        return items.findAllByEventIdOrderByPosition(eventId).stream().map(SetlistQuery::entry).toList();
    }

    public Optional<String> playlistUrl(Long eventId) {
        return pages.findById(eventId).map(SetlistPage::getYoutubePlaylistId).map(id -> PLAYLIST_URL_PREFIX + id);
    }

    // 없는 행사는 404, 페이지가 아직 없는 행사는 빈 페이지
    public SetlistView page(Long eventId) {
        requireEvent(eventId);
        return pages.findById(eventId)
                .map(page -> new SetlistView(eventId, page.getVersion(), page.isOrderConfirmed(), itemsOf(eventId), page.getUpdatedAt()))
                .orElseGet(() -> new SetlistView(eventId, 0, false, List.of(), null));
    }

    // 최신순
    public List<RevisionView> history(Long eventId) {
        requireEvent(eventId);
        return revisions.findAllByEventIdOrderByVersionDesc(eventId).stream()
                .map(r -> new RevisionView(r.getVersion(), r.getCreatedAt(), r.getSource(),
                        SnapshotCodec.decode(r.getSnapshot()).stream()
                                .map(e -> new SetlistEntry(e.songId(), e.position(), e.inSetlistFm())).toList()))
                .toList();
    }

    private void requireEvent(Long eventId) {
        if (eventQuery.find(eventId).isEmpty()) {
            throw new NotFoundException(ErrorCode.EVENT_NOT_FOUND);
        }
    }

    static SetlistEntry entry(SetlistItem item) {
        return new SetlistEntry(item.getSongId(), item.getPosition(), item.isInSetlistFm());
    }
}
