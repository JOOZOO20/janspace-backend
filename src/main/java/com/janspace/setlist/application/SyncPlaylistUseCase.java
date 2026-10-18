package com.janspace.setlist.application;

import com.janspace.catalog.application.CatalogQuery;
import com.janspace.catalog.application.SongSnapshot;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import com.janspace.ops.application.SyncRunRecorder;
import com.janspace.setlist.application.port.PlaylistPort;
import com.janspace.setlist.domain.SetlistPage;
import com.janspace.setlist.domain.YouTubeCredential;
import com.janspace.setlist.domain.YouTubeCredentialRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유튜브 재생목록 동기화: 첫 필요 시 "잔나비 {날짜} {공연장} 셋리" 를 만들고, 이후 위키 순서대로 항목을 맞춘다.
 * 영상 ID 가 없는 곡은 건너뛴다. 실행마다 sync_runs(YOUTUBE) 에 남긴다. 셋리 변경 뒤 리스너가 커밋 후 비동기로 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncPlaylistUseCase {

    static final String YOUTUBE = "YOUTUBE";

    private final SetlistWriter writer;
    private final YouTubeCredentialRepository credentials;
    private final PlaylistPort playlists;
    private final CatalogQuery catalogQuery;
    private final EventQuery eventQuery;
    private final SyncRunRecorder syncRuns;

    // 채널 미연결이면 조용히 건너뛴다 (자동 호출용). 관리자 수동 호출은 syncOrThrow
    @Transactional
    public void syncIfConnected(Long eventId) {
        if (!credentials.existsById(YouTubeCredential.SINGLETON_ID)) {
            log.info("[재생목록 동기화 건너뜀] 유튜브 채널 미연결 eventId={}", eventId);
            return;
        }
        sync(eventId);
    }

    @Transactional
    public String syncOrThrow(Long eventId) {
        if (!credentials.existsById(YouTubeCredential.SINGLETON_ID)) {
            throw new ConflictException(ErrorCode.YOUTUBE_NOT_CONNECTED);
        }
        return sync(eventId);
    }

    // 재생목록 ID 를 돌려준다
    private String sync(Long eventId) {
        SetlistPage page = writer.pageOf(eventId);
        Long runId = syncRuns.start(YOUTUBE);
        try {
            // 1. 위키 순서의 영상 ID (없는 곡은 제외)
            List<SetlistEntry> entries = writer.currentEntries(eventId);
            Map<Long, SongSnapshot> songs = catalogQuery.findSongs(entries.stream().map(SetlistEntry::songId).toList()).stream()
                    .collect(Collectors.toMap(SongSnapshot::id, Function.identity()));
            List<String> videoIds = entries.stream().map(e -> songs.get(e.songId())).filter(Objects::nonNull)
                    .map(SongSnapshot::youtubeVideoId).filter(Objects::nonNull).toList();
            // 2. 재생목록이 없거나 유튜브에서 지워졌으면 새로 만든다 (외부 호출)
            String playlistId = page.getYoutubePlaylistId();
            if (playlistId == null || playlists.currentTitle(playlistId).isEmpty()) {
                EventSnapshot event = eventQuery.find(eventId).orElseThrow();
                playlistId = playlists.create(title(event), "잔스페이스 셋리 위키에서 자동으로 만든 재생목록입니다.");
                page.changePlaylist(playlistId);
            }
            // 3. 항목 맞추기 (외부 호출)
            playlists.replaceItems(playlistId, videoIds);
            syncRuns.succeed(runId, "eventId=" + eventId + " playlistId=" + playlistId + " videos=" + videoIds.size());
            log.info("[재생목록 동기화 완료] eventId={}, playlistId={}, videos={}", eventId, playlistId, videoIds.size());
            return playlistId;
        } catch (RuntimeException e) {
            syncRuns.fail(runId, "eventId=" + eventId + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    static String title(EventSnapshot event) {
        String venue = event.venue() == null ? "" : " " + event.venue().name();
        return "잔나비 " + event.eventDate() + venue + " 셋리";
    }
}
