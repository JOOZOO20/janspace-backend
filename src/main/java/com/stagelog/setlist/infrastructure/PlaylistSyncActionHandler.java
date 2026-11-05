package com.stagelog.setlist.infrastructure;

import com.stagelog.ops.application.port.AgentActionHandler;
import com.stagelog.setlist.application.SyncPlaylistUseCase;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인된 MCP 조치 RETRY_PLAYLIST_SYNC: 관리자 수동 재생목록 동기화와 같은 경로(syncOrThrow)를 탄다. targetRef 는 행사 ID.
 */
@Component
@RequiredArgsConstructor
public class PlaylistSyncActionHandler implements AgentActionHandler {

    private final SyncPlaylistUseCase syncPlaylist;

    @Override
    public String type() {
        return "RETRY_PLAYLIST_SYNC";
    }

    @Override
    public void execute(String targetRef, Map<String, Object> payload) {
        syncPlaylist.syncOrThrow(Long.valueOf(targetRef));
    }
}
