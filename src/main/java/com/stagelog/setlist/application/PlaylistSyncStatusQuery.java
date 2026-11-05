package com.stagelog.setlist.application;

import com.stagelog.catalog.application.CatalogQuery;
import com.stagelog.catalog.application.SongSnapshot;
import com.stagelog.ops.application.SyncRunQuery;
import com.stagelog.ops.application.SyncRunQuery.SyncRunSummary;
import com.stagelog.setlist.domain.ApiQuotaLedgerRepository;
import com.stagelog.setlist.domain.SetlistPage;
import com.stagelog.setlist.domain.SetlistPageRepository;
import com.stagelog.setlist.domain.SetlistRevision;
import com.stagelog.setlist.domain.SetlistRevisionRepository;
import com.stagelog.setlist.domain.YouTubeCredential;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진단용 재생목록 동기화 상태 (MCP get_playlist_sync_status 와 관리자 화면). YouTube 를 호출하지 않고 DB 에 기록된 것만 본다:
 * 그 행사의 마지막 YOUTUBE 실행과 요약(반영·건너뜀 수), 미룸 표시, 채널 연결 상태(invalid_at), 현재 셋리가 요구하는 영상 수와의 차이,
 * 마지막 실행 뒤의 셋리 변경(리비전), 오늘(태평양 날짜) 이 행사가 쓴 Quota.
 * 한계: 유튜브 쪽 실제 항목은 마지막 실행이 맞춘 결과로만 추정한다. 실행 뒤 유튜브에서 직접 바뀐 것은 보이지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaylistSyncStatusQuery {

    // SyncPlaylistUseCase 요약 형식: "eventId=.. playlistId=.. videos=N skipped=M"
    static final Pattern SUMMARY_COUNTS = Pattern.compile("videos=(\\d+) skipped=(\\d+)");

    private final SetlistQuery setlistQuery;
    private final SetlistPageRepository pages;
    private final SetlistRevisionRepository revisions;
    private final YouTubeCredentialRepository credentials;
    private final ApiQuotaLedgerRepository ledger;
    private final YouTubeQuota quota;
    private final CatalogQuery catalogQuery;
    private final SyncRunQuery syncRunQuery;

    /** syncedVideos·skippedVideos 는 요약에서 읽은 값이며 요약에 없으면(실패·미룸) null. */
    public record LastRun(SyncRunSummary run, Integer syncedVideos, Integer skippedVideos) {
    }

    public record MissingVideo(Long songId, int position, String title) {
    }

    /**
     * videoCountGap = 현재 셋리의 영상 수 - 마지막 실행이 반영한 수 (마지막 실행 요약이 없으면 null).
     * setlistChangedAfterLastRun = 마지막 실행 종료 뒤 새 리비전이 생겼는지.
     */
    public record PlaylistStatus(Long eventId, String playlistId, Instant deferredAt, boolean connected, Instant credentialInvalidAt,
                                 LastRun lastRun, int setlistVersion, Long latestRevisionId, Instant latestRevisionAt,
                                 boolean setlistChangedAfterLastRun, int targetVideoCount, Integer videoCountGap,
                                 List<MissingVideo> songsWithoutVideo, LocalDate quotaDate, long quotaUnitsToday) {
    }

    public PlaylistStatus status(Long eventId) {
        // 없는 행사는 404
        SetlistView view = setlistQuery.page(eventId);
        Optional<SetlistPage> page = pages.findById(eventId);
        Optional<YouTubeCredential> credential = credentials.findById(YouTubeCredential.SINGLETON_ID);
        Optional<SetlistRevision> revision = revisions.findFirstByEventIdOrderByVersionDesc(eventId);

        // 1. 현재 셋리가 요구하는 영상: 곡 마스터에 영상 ID 가 있는 항목. 없는 곡은 동기화가 건너뛴다
        Map<Long, SongSnapshot> songs = catalogQuery.findSongs(view.entries().stream().map(SetlistEntry::songId).toList()).stream()
                .collect(Collectors.toMap(SongSnapshot::id, Function.identity()));
        int target = 0;
        List<MissingVideo> missing = new ArrayList<>();
        for (SetlistEntry entry : view.entries()) {
            SongSnapshot song = songs.get(entry.songId());
            if (song != null && song.youtubeVideoId() != null) {
                target++;
            } else {
                missing.add(new MissingVideo(entry.songId(), entry.position(), song == null ? null : song.title().original()));
            }
        }

        // 2. 마지막 실행과 그 뒤 셋리 변경
        LastRun lastRun = syncRunQuery.latestForEvent(SyncPlaylistUseCase.YOUTUBE, eventId).map(PlaylistSyncStatusQuery::lastRun)
                .orElse(null);
        Instant runEnd = lastRun == null ? null : lastRun.run().finishedAt();
        boolean changedAfter = revision.isPresent() && (runEnd == null || revision.get().getCreatedAt().isAfter(runEnd));
        Integer gap = lastRun == null || lastRun.syncedVideos() == null ? null : target - lastRun.syncedVideos();

        LocalDate today = quota.today();
        return new PlaylistStatus(eventId, page.map(SetlistPage::getYoutubePlaylistId).orElse(null),
                page.map(SetlistPage::getYoutubeSyncDeferredAt).orElse(null), credential.isPresent(),
                credential.map(YouTubeCredential::getInvalidAt).orElse(null), lastRun, view.version(),
                revision.map(SetlistRevision::getId).orElse(null), revision.map(SetlistRevision::getCreatedAt).orElse(null),
                changedAfter, target, gap, List.copyOf(missing), today, ledger.eventUnits(YouTubeQuota.PROVIDER, today, eventId));
    }

    private static LastRun lastRun(SyncRunSummary run) {
        Matcher m = run.summary() == null ? null : SUMMARY_COUNTS.matcher(run.summary());
        return m != null && m.find() ? new LastRun(run, Integer.valueOf(m.group(1)), Integer.valueOf(m.group(2)))
                : new LastRun(run, null, null);
    }
}
