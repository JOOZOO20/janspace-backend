package com.janspace.setlist.infrastructure;

import com.janspace.event.application.port.PlaylistUrlPort;
import com.janspace.setlist.application.SetlistQuery;
import com.janspace.setlist.domain.SetlistPage;
import com.janspace.setlist.domain.SetlistPageRepository;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * event 응답의 playlistUrl 공급 (event/application/port/PlaylistUrlPort 구현).
 */
@Component
@RequiredArgsConstructor
public class PlaylistUrlAdapter implements PlaylistUrlPort {

    private final SetlistPageRepository pages;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, String> playlistUrls(Collection<Long> eventIds) {
        return pages.findAllByEventIdIn(eventIds).stream()
                .filter(p -> p.getYoutubePlaylistId() != null)
                .collect(Collectors.toMap(SetlistPage::getEventId, p -> SetlistQuery.PLAYLIST_URL_PREFIX + p.getYoutubePlaylistId()));
    }
}
