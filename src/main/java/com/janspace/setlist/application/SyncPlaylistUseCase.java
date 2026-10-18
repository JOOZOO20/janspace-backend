package com.janspace.setlist.application;

import com.janspace.catalog.application.CatalogQuery;
import com.janspace.catalog.application.SongSnapshot;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSnapshot;
import com.janspace.ops.application.OperatorAlert;
import com.janspace.ops.application.SyncRunRecorder;
import com.janspace.setlist.application.port.PlaylistPort;
import com.janspace.setlist.application.port.PlaylistSyncLock;
import com.janspace.setlist.domain.SetlistPage;
import com.janspace.setlist.domain.SetlistPageRepository;
import com.janspace.setlist.domain.YouTubeCredential;
import com.janspace.setlist.domain.YouTubeCredentialRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 유튜브 재생목록 동기화: 첫 필요 시 "잔나비 {날짜} {공연장} 셋리" 를 만들고, 이후 위키 순서대로 항목을 맞춘다.
 * 영상 ID 가 없는 곡은 건너뛰고, 유튜브에서 지워진 영상은 빼고 넣은 뒤 운영자에게 알린다. 실행마다 sync_runs(YOUTUBE) 에 남긴다. 셋리 변경 뒤 리스너가 커밋 후 비동기로 부른다.
 * 재생목록 확보(확인·생성·ID 저장)만 페이지 행 잠금을 건 짧은 트랜잭션에서 커밋하고, 항목 맞추기는 트랜잭션 밖에서 한다.
 * 한 행사의 동기화는 Redis 잠금(PlaylistSyncLock)으로 하나만 돈다. 잠금 때문에 건너뛴 동기화의 변경은 돌고 있던 쪽이
 * 항목을 맞춘 뒤 셋리 version 을 다시 보고 달라졌으면 새 셋리로 한 번 더 맞춰 반영한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncPlaylistUseCase {

    static final String YOUTUBE = "YOUTUBE";
    static final String DESCRIPTION = "잔스페이스 셋리 위키에서 자동으로 만든 재생목록입니다.";
    // ponytail: 맞추는 동안 셋리가 계속 바뀌면 3번에서 멈춘다. 남은 변경은 다음 셋리 변경의 동기화가 맞춘다
    static final int MAX_PASSES = 3;

    private final SetlistWriter writer;
    private final SetlistPageRepository pages;
    private final SetlistQuery setlistQuery;
    private final YouTubeCredentialRepository credentials;
    private final PlaylistPort playlists;
    private final CatalogQuery catalogQuery;
    private final EventQuery eventQuery;
    private final SyncRunRecorder syncRuns;
    private final OperatorAlert operatorAlert;
    private final TransactionTemplate transactionTemplate;
    private final PlaylistSyncLock syncLock;

    // 채널 미연결이면 조용히 건너뛴다 (자동 호출용). 관리자 수동 호출은 syncOrThrow
    public void syncIfConnected(Long eventId) {
        if (!credentials.existsById(YouTubeCredential.SINGLETON_ID)) {
            log.info("[재생목록 동기화 건너뜀] 유튜브 채널 미연결 eventId={}", eventId);
            return;
        }
        if (syncLocked(eventId).isEmpty()) {
            log.info("[재생목록 동기화 건너뜀] 같은 행사 동기화 진행 중 — 진행 중인 쪽이 셋리 version 을 다시 보고 맞춘다 eventId={}", eventId);
        }
    }

    // 같은 행사 동기화가 돌고 있으면 409. 곡 마스터의 영상 ID 를 고친 뒤 부르는 경우가 있어(셋리 version 은 그대로) 건너뛰지 않는다
    public String syncOrThrow(Long eventId) {
        if (!credentials.existsById(YouTubeCredential.SINGLETON_ID)) {
            throw new ConflictException(ErrorCode.YOUTUBE_NOT_CONNECTED);
        }
        return syncLocked(eventId).orElseThrow(() -> new ConflictException(ErrorCode.PLAYLIST_SYNC_IN_PROGRESS));
    }

    // 잠금을 잡으면 동기화하고 재생목록 ID 를, 다른 동기화가 잡고 있으면 empty
    private Optional<String> syncLocked(Long eventId) {
        Optional<String> token = syncLock.tryAcquire(eventId);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(sync(eventId));
        } finally {
            syncLock.release(eventId, token.get());
        }
    }

    // 재생목록 ID 를 돌려준다. 메서드 전체를 트랜잭션으로 묶지 않는다: 항목 맞추기(외부 호출 수십 번) 동안
    // DB 연결과 페이지 행 잠금을 잡고 있으면 같은 행사의 셋리 편집·병합이 그만큼 기다린다
    private String sync(Long eventId) {
        // 없는 행사는 404. 페이지가 없으면 여기서 만들어 두어야 2단계가 그 행을 잠글 수 있다
        transactionTemplate.executeWithoutResult(status -> writer.pageOf(eventId));
        Long runId = syncRuns.start(YOUTUBE);
        try {
            // 1. 재생목록 확보 후 바로 커밋. 2단계가 실패해도 만든 ID 가 남아 다음 동기화가 같은 재생목록을 쓴다
            String playlistId = transactionTemplate.execute(status -> ensurePlaylist(eventId));
            // 2. 위키 순서의 영상 ID(없는 곡은 제외)로 항목 맞추기 (외부 호출, 트랜잭션 밖). 유튜브에 없는 영상은 어댑터가 건너뛰고 돌려준다.
            //    맞추는 사이 셋리 version 이 바뀌었으면(잠금 때문에 건너뛴 동기화의 변경) 새 셋리로 다시 맞춘다
            SetlistView view = setlistQuery.page(eventId);
            List<SetlistEntry> entries;
            Map<Long, SongSnapshot> songs;
            List<String> videoIds;
            List<String> skipped;
            for (int pass = 1; ; pass++) {
                entries = view.entries();
                Map<Long, SongSnapshot> found = catalogQuery.findSongs(entries.stream().map(SetlistEntry::songId).toList()).stream()
                        .collect(Collectors.toMap(SongSnapshot::id, Function.identity()));
                songs = found;
                videoIds = entries.stream().map(e -> found.get(e.songId())).filter(Objects::nonNull)
                        .map(SongSnapshot::youtubeVideoId).filter(Objects::nonNull).toList();
                skipped = playlists.replaceItems(playlistId, videoIds);
                SetlistView latest = setlistQuery.page(eventId);
                if (latest.version() == view.version()) {
                    break;
                }
                if (pass == MAX_PASSES) {
                    log.warn("[재생목록 동기화 재실행 한도] eventId={}, 맞춘 version={}, 현재 version={}", eventId, view.version(), latest.version());
                    break;
                }
                log.info("[재생목록 동기화 재실행] 맞추는 동안 셋리가 바뀜 eventId={}, version {} -> {}", eventId, view.version(), latest.version());
                view = latest;
            }
            int added = videoIds.size() - skipped.size();
            syncRuns.succeed(runId, "eventId=" + eventId + " playlistId=" + playlistId + " videos=" + added + " skipped=" + skipped.size());
            log.info("[재생목록 동기화 완료] eventId={}, playlistId={}, videos={}, skipped={}", eventId, playlistId, added, skipped.size());
            // 4. 건너뛴 곡은 실행마다 한 번 묶어 알린다. 대체 영상은 관리자가 고르므로 곡 마스터는 건드리지 않는다
            if (!skipped.isEmpty()) {
                operatorAlert.send(missingVideoAlert(eventId, playlistId, entries, songs, skipped));
            }
            return playlistId;
        } catch (RuntimeException e) {
            syncRuns.fail(runId, "eventId=" + eventId + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    // 같은 행사의 동기화가 동시에 돌면(편집·병합 직후 자동 + 관리자 수동) 둘 다 ID 없음을 보고 하나씩 만들 수 있다.
    // 페이지 행을 SELECT ... FOR UPDATE 로 잡아 확인·생성을 줄 세운다. 뒤에 온 쪽은 앞쪽 커밋 뒤의 ID 를 읽는다
    // 한계: 잠금 동안 외부 호출(확인 1번, 없으면 생성 1번)을 한다. 유튜브에서 지워진 재생목록은 어댑터가 전파 지연으로 보고
    // 최대 15초 다시 확인하므로, 그 드문 경우 같은 행사의 셋리 편집이 그만큼 기다린다
    private String ensurePlaylist(Long eventId) {
        SetlistPage page = pages.findByIdForUpdate(eventId).orElseThrow();
        String playlistId = page.getYoutubePlaylistId();
        // 외부 호출: 유튜브에서 지워졌는지 확인
        if (playlistId != null && playlists.currentTitle(playlistId).isPresent()) {
            return playlistId;
        }
        // 외부 호출: 생성. ID 는 이 트랜잭션 커밋으로 저장된다
        EventSnapshot event = eventQuery.find(eventId).orElseThrow();
        String created = playlists.create(title(event), DESCRIPTION);
        page.changePlaylist(created);
        log.info("[재생목록 생성] eventId={}, playlistId={}, replaced={}", eventId, created, playlistId);
        return created;
    }

    // 같은 곡이 셋리에 두 번 있어도 한 번. 디스코드가 2000자에서 자르므로 사유·할 일을 앞에, 곡 목록을 뒤에 둔다
    private static String missingVideoAlert(Long eventId, String playlistId, List<SetlistEntry> entries, Map<Long, SongSnapshot> songs,
                                            List<String> skipped) {
        Set<String> missing = Set.copyOf(skipped);
        String lines = entries.stream().map(e -> songs.get(e.songId())).filter(Objects::nonNull)
                .filter(song -> missing.contains(song.youtubeVideoId())).distinct()
                .map(song -> "songId=" + song.id() + " \"" + song.title().original() + "\" videoId=" + song.youtubeVideoId())
                .collect(Collectors.joining(", "));
        return "[재생목록 영상 없음] eventId=" + eventId + " playlistId=" + playlistId + " 건너뜀 " + skipped.size() + "건"
                + " (사유: 유튜브 videoNotFound — 영상 삭제·비공개 또는 잘못된 ID). PUT /api/v1/admin/songs 로 youtubeId 를 바꾼 뒤"
                + " POST /api/v1/admin/events/" + eventId + "/setlist/playlist 로 다시 동기화: " + lines;
    }

    static String title(EventSnapshot event) {
        String venue = event.venue() == null ? "" : " " + event.venue().name();
        return "잔나비 " + event.eventDate() + venue + " 셋리";
    }
}
