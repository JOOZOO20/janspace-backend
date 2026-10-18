package com.janspace.event.application.port;

import java.util.Collection;
import java.util.Map;

/**
 * 행사 응답의 playlistUrl 공급 포트. 구현은 setlist/infrastructure(PlaylistUrlAdapter). 빈이 없으면 event 는 null 로 내린다.
 */
public interface PlaylistUrlPort {

    // 재생목록이 있는 행사만 담긴다 (eventId -> 공개 URL)
    Map<Long, String> playlistUrls(Collection<Long> eventIds);
}
