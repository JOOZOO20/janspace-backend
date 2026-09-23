package com.janspace.setlist.application.port;

import java.util.List;
import java.util.Optional;

/**
 * 유튜브 재생목록 포트. 채널 미연결이면 409 YOUTUBE_NOT_CONNECTED, API 실패면 502 YOUTUBE_API_ERROR 를 던진다.
 */
public interface PlaylistPort {

    // 만들어진 재생목록 ID
    String create(String title, String description);

    // 재생목록 항목을 주어진 영상 순서와 같게 맞춘다
    void replaceItems(String playlistId, List<String> videoIds);

    // 재생목록이 유튜브에서 지워졌으면 empty
    Optional<String> currentTitle(String playlistId);
}
