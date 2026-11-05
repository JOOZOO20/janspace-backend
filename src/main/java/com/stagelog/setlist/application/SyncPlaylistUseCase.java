package com.stagelog.setlist.application;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.catalog.application.CatalogQuery;
import com.stagelog.catalog.application.SongSnapshot;
import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.event.application.EventQuery;
import com.stagelog.event.application.EventSnapshot;
import com.stagelog.ops.application.OperatorAlert;
import com.stagelog.ops.application.SyncRunRecorder;
import com.stagelog.setlist.application.port.PlaylistPort;
import com.stagelog.setlist.application.port.PlaylistPort.ItemSync;
import com.stagelog.setlist.application.port.PlaylistPort.Outcome;
import com.stagelog.setlist.application.port.PlaylistSyncLock;
import com.stagelog.setlist.domain.PlaylistSyncPriority;
import com.stagelog.setlist.domain.QuotaBucket;
import com.stagelog.setlist.domain.SetlistPage;
import com.stagelog.setlist.domain.SetlistPageRepository;
import com.stagelog.setlist.domain.YouTubeCredential;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 유튜브 재생목록 동기화: 첫 필요 시 "{앞머리} {날짜} {공연장} 셋리" 를 만들고(앞머리는 설정값, 보통 아티스트 이름), 이후 위키 순서대로 항목을 맞춘다.
 * 영상 ID 가 없는 곡은 건너뛰고, 유튜브에서 지워진 영상은 빼고 넣은 뒤 운영자에게 알린다. 실행마다 sync_runs(YOUTUBE) 에 남긴다. 셋리 변경 뒤 리스너가 커밋 후 비동기로 부른다.
 * 재생목록 확보(확인·생성·ID 저장)만 페이지 행 잠금을 건 짧은 트랜잭션에서 커밋하고, 항목 맞추기는 트랜잭션 밖에서 한다.
 * 한 행사의 동기화는 Redis 잠금(PlaylistSyncLock)으로 하나만 돈다. 잠금 때문에 건너뛴 동기화의 변경은 돌고 있던 쪽이
 * 항목을 맞춘 뒤 셋리 version 을 다시 보고 달라졌으면 새 셋리로 한 번 더 맞춰 반영한다.
 * Quota: 자동 동기화는 AUTO, 관리자 수동은 MANUAL 예산을 쓴다. 자동은 공연 날짜로 우선순위(PlaylistSyncPriority)를 정해 낮은 우선순위일수록
 * 예산의 더 작은 몫까지만 예약한다. 예약에 실패하거나 API 가 quotaExceeded 를 주면 run 을 DEFERRED 로 닫고 페이지에 표시해 두며,
 * 다음 리셋 뒤 retryDeferred 가 우선순위 순서로 다시 돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncPlaylistUseCase {

    static final String YOUTUBE = "YOUTUBE";
    static final String DESCRIPTION = "Stagelog 셋리 위키에서 자동으로 만든 재생목록입니다.";
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
    // 생성자 주입 대상이 아니라 final 이 아니다 (@RequiredArgsConstructor 가 @Value 를 옮기지 않는다)
    @Value("${stagelog.youtube.playlist-title-prefix:}")
    private String titlePrefix;

    // 채널 미연결이거나 연결이 만료됐으면 조용히 건너뛴다 (자동 호출용). 관리자 수동 호출은 syncOrThrow.
    // Quota 부족은 DEFERRED 로 남기고 예외 없이 끝난다 (리셋 뒤 retryDeferred 가 다시 돌린다)
    public void syncIfConnected(Long eventId) {
        Optional<YouTubeCredential> credential = credentials.findById(YouTubeCredential.SINGLETON_ID);
        if (credential.isEmpty() || credential.get().getInvalidAt() != null) {
            log.info("[재생목록 동기화 건너뜀] 유튜브 채널 미연결 또는 연결 만료 eventId={}", eventId);
            return;
        }
        try {
            if (syncLocked(eventId, QuotaBucket.AUTO).isEmpty()) {
                log.info("[재생목록 동기화 건너뜀] 같은 행사 동기화 진행 중 — 진행 중인 쪽이 셋리 version 을 다시 보고 맞춘다 eventId={}", eventId);
            }
        } catch (ApplicationException e) {
            if (e.getErrorCode() != ErrorCode.YOUTUBE_QUOTA_EXHAUSTED) {
                throw e;
            }
            log.info("[재생목록 동기화 미룸] 유튜브 할당량 부족 eventId={}", eventId);
        }
    }

    // 리셋 뒤 스케줄러가 부른다. 미룬 행사를 우선순위(공연 당일·전후 > 7일 안 > 나머지), 같은 우선순위는 공연 날짜 순으로 다시 동기화한다.
    // 예산이 모자라면 낮은 우선순위부터 다시 DEFERRED 로 남는다
    public void retryDeferred() {
        List<Long> deferred = pages.findPlaylistDeferredEventIds();
        if (deferred.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now(EventQuery.LIST_ZONE);
        List<EventSnapshot> events = eventQuery.findByIds(deferred).stream()
                .sorted(Comparator.comparing((EventSnapshot e) -> PlaylistSyncPriority.of(e.eventDate(), today))
                        .thenComparing(EventSnapshot::eventDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        log.info("[미룬 재생목록 동기화 재시도] events={}", events.size());
        for (EventSnapshot event : events) {
            try {
                syncIfConnected(event.id());
            } catch (RuntimeException e) {
                log.error("[미룬 재생목록 동기화 실패] eventId={}", event.id(), e);
            }
        }
    }

    // 같은 행사 동기화가 돌고 있으면 409. 곡 마스터의 영상 ID 를 고친 뒤 부르는 경우가 있어(셋리 version 은 그대로) 건너뛰지 않는다
    public String syncOrThrow(Long eventId) {
        YouTubeCredential credential = credentials.findById(YouTubeCredential.SINGLETON_ID)
                .orElseThrow(() -> new ConflictException(ErrorCode.YOUTUBE_NOT_CONNECTED));
        if (credential.getInvalidAt() != null) {
            throw new ConflictException(ErrorCode.YOUTUBE_RECONNECT_REQUIRED);
        }
        return syncLocked(eventId, QuotaBucket.MANUAL).orElseThrow(() -> new ConflictException(ErrorCode.PLAYLIST_SYNC_IN_PROGRESS));
    }

    // 잠금을 잡으면 동기화하고 재생목록 ID 를, 다른 동기화가 잡고 있으면 empty
    private Optional<String> syncLocked(Long eventId, QuotaBucket bucket) {
        Optional<String> token = syncLock.tryAcquire(eventId);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(sync(eventId, bucket));
        } finally {
            syncLock.release(eventId, token.get());
        }
    }

    // 재생목록 ID 를 돌려준다. 메서드 전체를 트랜잭션으로 묶지 않는다: 항목 맞추기(외부 호출 수십 번) 동안
    // DB 연결과 페이지 행 잠금을 잡고 있으면 같은 행사의 셋리 편집·병합이 그만큼 기다린다
    private String sync(Long eventId, QuotaBucket bucket) {
        // 없는 행사는 404. 페이지가 없으면 여기서 만들어 두어야 2단계가 그 행을 잠글 수 있다
        transactionTemplate.executeWithoutResult(status -> writer.pageOf(eventId));
        Long runId = syncRuns.start(YOUTUBE);
        QuotaScope scope = scope(eventId, bucket, runId);
        try {
            // 1. 재생목록 확보 후 바로 커밋. 2단계가 실패해도 만든 ID 가 남아 다음 동기화가 같은 재생목록을 쓴다
            String playlistId = transactionTemplate.execute(status -> ensurePlaylist(eventId, scope));
            // 2. 위키 순서의 영상 ID(없는 곡은 제외)로 항목 맞추기 (외부 호출, 트랜잭션 밖). 유튜브에 없는 영상은 어댑터가 건너뛰고 돌려준다.
            //    맞추는 사이 셋리 version 이 바뀌었으면(잠금 때문에 건너뛴 동기화의 변경) 새 셋리로 다시 맞춘다
            SetlistView view = setlistQuery.page(eventId);
            List<SetlistEntry> entries;
            Map<Long, SongSnapshot> songs;
            List<String> videoIds;
            ItemSync result;
            for (int pass = 1; ; pass++) {
                entries = view.entries();
                Map<Long, SongSnapshot> found = catalogQuery.findSongs(entries.stream().map(SetlistEntry::songId).toList()).stream()
                        .collect(Collectors.toMap(SongSnapshot::id, Function.identity()));
                songs = found;
                videoIds = entries.stream().map(e -> found.get(e.songId())).filter(Objects::nonNull)
                        .map(SongSnapshot::youtubeVideoId).filter(Objects::nonNull).toList();
                result = playlists.syncItems(playlistId, videoIds, scope);
                SetlistView latest = setlistQuery.page(eventId);
                // PARTIAL·DIFF_INVALID 는 다시 맞추지 않는다 (같은 문제로 Quota 만 더 쓴다)
                if (result.outcome() != Outcome.OK || latest.version() == view.version()) {
                    break;
                }
                if (pass == MAX_PASSES) {
                    log.warn("[재생목록 동기화 재실행 한도] eventId={}, 맞춘 version={}, 현재 version={}", eventId, view.version(), latest.version());
                    break;
                }
                log.info("[재생목록 동기화 재실행] 맞추는 동안 셋리가 바뀜 eventId={}, version {} -> {}", eventId, view.version(), latest.version());
                view = latest;
            }
            List<String> skipped = result.skipped();
            int added = videoIds.size() - skipped.size();
            String summary = "eventId=" + eventId + " playlistId=" + playlistId + " videos=" + added + " skipped=" + skipped.size();
            if (result.outcome() == Outcome.DIFF_INVALID) {
                // 계산 결과가 목표에 닿지 않는 diff 버그. 호출은 하지 않았다
                syncRuns.fail(runId, result.detail() + " " + summary);
                operatorAlert.send("[재생목록 diff 검증 실패] eventId=" + eventId + " playlistId=" + playlistId
                        + " 계산한 연산이 목표 순서에 닿지 않아 유튜브를 호출하지 않았습니다(DIFF_INVALID). 서버 로그를 확인해 주세요.");
                return playlistId;
            }
            transactionTemplate.executeWithoutResult(status -> pages.clearPlaylistDeferred(eventId));
            if (result.outcome() == Outcome.PARTIAL) {
                syncRuns.partial(runId, result.detail() + " " + summary);
                log.warn("[재생목록 동기화 일부 반영] eventId={}, playlistId={}, detail={}", eventId, playlistId, result.detail());
                operatorAlert.send("[재생목록 일부 반영] eventId=" + eventId + " playlistId=" + playlistId + " 바뀐 항목을 반영한 뒤 다시 읽은 목록이 목표와 다릅니다("
                        + result.detail() + "). Quota 보호를 위해 전체 재구성은 하지 않았습니다. POST " + V1_BASE + "/admin/events/" + eventId
                        + "/setlist/playlist 로 다시 동기화해 주세요.");
            } else {
                syncRuns.succeed(runId, summary);
            }
            log.info("[재생목록 동기화 완료] eventId={}, playlistId={}, videos={}, skipped={}, writes={}", eventId, playlistId, added, skipped.size(),
                    result.writes());
            // 4. 건너뛴 곡은 실행마다 한 번 묶어 알린다. 대체 영상은 관리자가 고르므로 곡 마스터는 건드리지 않는다
            if (!skipped.isEmpty()) {
                operatorAlert.send(missingVideoAlert(eventId, playlistId, entries, songs, skipped));
            }
            return playlistId;
        } catch (ApplicationException e) {
            if (e.getErrorCode() == ErrorCode.YOUTUBE_QUOTA_EXHAUSTED) {
                syncRuns.defer(runId, "eventId=" + eventId + " QUOTA_EXHAUSTED bucket=" + scope.bucket() + " ceilingPercent=" + scope.ceilingPercent());
                transactionTemplate.executeWithoutResult(status -> pages.markPlaylistDeferred(eventId));
                throw e;
            }
            failed(runId, eventId, e);
            throw e;
        } catch (RuntimeException e) {
            failed(runId, eventId, e);
            throw e;
        }
    }

    private void failed(Long runId, Long eventId, RuntimeException e) {
        syncRuns.fail(runId, "eventId=" + eventId + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
        if (e instanceof ApplicationException ae && ae.getErrorCode() == ErrorCode.YOUTUBE_RECONNECT_REQUIRED) {
            markCredentialInvalid();
        }
    }

    // 자동은 공연 날짜로 정한 우선순위의 상한까지, 수동은 MANUAL 예산 전부
    private QuotaScope scope(Long eventId, QuotaBucket bucket, Long runId) {
        if (bucket == QuotaBucket.MANUAL) {
            return QuotaScope.manual(eventId, runId);
        }
        LocalDate eventDate = eventQuery.find(eventId).map(EventSnapshot::eventDate).orElse(null);
        PlaylistSyncPriority priority = PlaylistSyncPriority.of(eventDate, LocalDate.now(EventQuery.LIST_ZONE));
        return new QuotaScope(QuotaBucket.AUTO, priority.getCeilingPercent(), eventId, runId);
    }

    // 같은 행사의 동기화가 동시에 돌면(편집·병합 직후 자동 + 관리자 수동) 둘 다 ID 없음을 보고 하나씩 만들 수 있다.
    // 페이지 행을 SELECT ... FOR UPDATE 로 잡아 확인·생성을 줄 세운다. 뒤에 온 쪽은 앞쪽 커밋 뒤의 ID 를 읽는다
    // 한계: 잠금 동안 외부 호출(확인 1번, 없으면 생성 1번)을 한다. 유튜브에서 지워진 재생목록은 어댑터가 전파 지연으로 보고
    // 최대 15초 다시 확인하므로, 그 드문 경우 같은 행사의 셋리 편집이 그만큼 기다린다
    private String ensurePlaylist(Long eventId, QuotaScope scope) {
        SetlistPage page = pages.findByIdForUpdate(eventId).orElseThrow();
        String playlistId = page.getYoutubePlaylistId();
        // 외부 호출: 유튜브에서 지워졌는지 확인
        if (playlistId != null && playlists.currentTitle(playlistId, scope).isPresent()) {
            return playlistId;
        }
        // 외부 호출: 생성. ID 는 이 트랜잭션 커밋으로 저장된다
        EventSnapshot event = eventQuery.find(eventId).orElseThrow();
        String created = playlists.create(title(event), DESCRIPTION, scope);
        page.changePlaylist(created);
        log.info("[재생목록 생성] eventId={}, playlistId={}, replaced={}", eventId, created, playlistId);
        return created;
    }

    // 리프레시 토큰 폐기·만료. 처음 표시한 쪽만 알린다. 이후 자동 동기화는 syncIfConnected 에서 건너뛴다
    private void markCredentialInvalid() {
        Integer marked = transactionTemplate.execute(status -> credentials.markInvalid(YouTubeCredential.SINGLETON_ID, Instant.now()));
        if (marked != null && marked == 1) {
            log.warn("[유튜브 연결 만료] 리프레시 토큰 갱신이 invalid_grant 로 실패해 자동 동기화를 멈춥니다");
            operatorAlert.send("[유튜브 연결 만료] 리프레시 토큰이 폐기·만료됐습니다(invalid_grant). 재생목록 자동 동기화를 멈췄습니다."
                    + " GET " + V1_BASE + "/admin/youtube/authorize 로 채널을 다시 연결해 주세요.");
        }
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
                + " (사유: 유튜브 videoNotFound — 영상 삭제·비공개 또는 잘못된 ID). PUT " + V1_BASE + "/admin/songs 로 youtubeId 를 바꾼 뒤"
                + " POST " + V1_BASE + "/admin/events/" + eventId + "/setlist/playlist 로 다시 동기화: " + lines;
    }

    String title(EventSnapshot event) {
        String venue = event.venue() == null ? "" : " " + event.venue().name();
        String prefix = titlePrefix == null || titlePrefix.isBlank() ? "" : titlePrefix.strip() + " ";
        return prefix + event.eventDate() + venue + " 셋리";
    }
}
