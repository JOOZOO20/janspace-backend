package com.stagelog.setlist.infrastructure;

import com.stagelog.ops.application.AdminAuditRecorder;
import com.stagelog.ops.application.AuditAction;
import com.stagelog.ops.application.AuditActor;
import com.stagelog.ops.application.port.AgentActionHandler;
import com.stagelog.setlist.application.SyncPlaylistUseCase;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 승인된 MCP 조치 RETRY_PLAYLIST_SYNC: 관리자 수동 재생목록 동기화와 같은 경로(syncOrThrow)를 탄다. targetRef 는 행사 ID.
 * 감사 로그도 수동 동기화(AdminSetlistController)와 같이 성공한 뒤 별도 트랜잭션으로 승인한 관리자 이름으로 남긴다.
 */
@Component
@RequiredArgsConstructor
public class PlaylistSyncActionHandler implements AgentActionHandler {

    private final SyncPlaylistUseCase syncPlaylist;
    private final AdminAuditRecorder audit;

    @Override
    public String type() {
        return "RETRY_PLAYLIST_SYNC";
    }

    @Override
    public void execute(String targetRef, Map<String, Object> payload, AuditActor approver) {
        String playlistId = syncPlaylist.syncOrThrow(Long.valueOf(targetRef));
        audit.recordInNewTransaction(approver, AuditAction.PLAYLIST_SYNC, "EVENT", targetRef, null, Map.of("playlistId", playlistId), null);
    }
}
