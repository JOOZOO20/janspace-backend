package com.stagelog.setlist.infrastructure;

import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.integration.youtube.YouTubeClient;
import com.stagelog.integration.youtube.YouTubePlaylistItem;
import com.stagelog.setlist.application.QuotaReservation;
import com.stagelog.setlist.application.QuotaScope;
import com.stagelog.setlist.application.YouTubeQuota;
import com.stagelog.setlist.application.port.PlaylistPort;
import com.stagelog.setlist.domain.PlaylistDiff;
import com.stagelog.setlist.domain.QuotaOperation;
import com.stagelog.setlist.domain.YouTubeCredential;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * PlaylistPort 의 YouTube Data API 구현. 호출마다 DB 의 리프레시 토큰으로 액세스 토큰을 새로 받는다.
 * 모든 Data API 호출은 YouTubeQuota 원장에 예약한 뒤 부른다: 목록 조회는 호출(페이지)마다 1, 항목 쓰기는 diff 를 계산한 뒤 합계를 한 번에 예약한다.
 * 한계: 액세스 토큰(1시간)을 캐시하지 않는다. 행사당 하루 몇 번 수준이라 토큰 요청 비용이 무시할 만하다. 잦아지면 만료 시각과 함께 메모리에 둔다
 */
@Slf4j
@Component
public class YouTubePlaylistAdapter implements PlaylistPort {

    // 갓 만든 재생목록은 몇 초 동안 다른 API 에 안 보인다(항목 추가·조회가 404 playlistNotFound, 재생목록 조회가 빈 결과).
    // 1·2·4·8초를 기다리며 다시 부르고, 그래도 안 보이면 그 결과(예외 또는 빈 결과)를 그대로 낸다. diff 적용 뒤 재조회 비교도 같은 간격이다
    static final int PROPAGATION_RETRIES = 4;
    static final long PROPAGATION_BACKOFF_MILLIS = 1_000;
    // playlistItems.insert 404 오류 본문의 reason
    static final String PLAYLIST_NOT_FOUND = "playlistNotFound";
    static final String VIDEO_NOT_FOUND = "videoNotFound";
    // 403 오류 본문의 reason. 일일 Quota 를 다 썼다는 뜻 (https://developers.google.com/youtube/v3/docs/errors)
    static final String QUOTA_EXCEEDED = "quotaExceeded";
    // 토큰 갱신 400 오류 본문의 error. 리프레시 토큰이 폐기·만료됐다는 뜻이라 다시 연결해야 한다
    static final String INVALID_GRANT = "invalid_grant";

    private final YouTubeClient client;
    private final YouTubeCredentialRepository credentials;
    private final YouTubeQuota quota;
    private final boolean diffSync;
    private final LongConsumer sleeper;

    @Autowired
    public YouTubePlaylistAdapter(YouTubeClient client, YouTubeCredentialRepository credentials, YouTubeQuota quota,
                                  @Value("${jan.youtube.diff-sync.enabled:true}") boolean diffSync) {
        this(client, credentials, quota, diffSync, YouTubePlaylistAdapter::sleep);
    }

    // 테스트는 sleeper 를 바꿔 실제로 기다리지 않는다
    public YouTubePlaylistAdapter(YouTubeClient client, YouTubeCredentialRepository credentials, YouTubeQuota quota, boolean diffSync,
                                  LongConsumer sleeper) {
        this.client = client;
        this.credentials = credentials;
        this.quota = quota;
        this.diffSync = diffSync;
        this.sleeper = sleeper;
    }

    @Override
    public String create(String title, String description, QuotaScope scope) {
        return call(() -> {
            String token = accessToken();
            return metered(scope, QuotaOperation.INSERT, () -> client.createPlaylist(token, title, description));
        });
    }

    @Override
    public ItemSync syncItems(String playlistId, List<String> videoIds, QuotaScope scope) {
        return call(() -> {
            String token = accessToken();
            List<YouTubePlaylistItem> current = untilVisible(playlistId, attempt -> listAll(token, playlistId, scope), items -> true);
            return diffSync ? applyDiff(token, playlistId, current, videoIds, scope) : rebuild(token, playlistId, current, videoIds, scope);
        });
    }

    @Override
    public Optional<String> currentTitle(String playlistId, QuotaScope scope) {
        return call(() -> {
            String token = accessToken();
            return untilVisible(playlistId, attempt -> metered(scope, QuotaOperation.LIST, () -> client.playlistTitle(token, playlistId)),
                    Optional::isPresent);
        });
    }

    // 바뀐 항목만 반영한다 (PlaylistDiff). 계획을 로컬에서 먼저 적용해 목표와 다르면 호출 없이 DIFF_INVALID.
    // 적용 뒤 다시 읽어 비교하고 1·2·4·8초 재조회 뒤에도 다르면 PARTIAL. Quota 보호를 위해 전체 재구성으로 되돌리지 않는다
    private ItemSync applyDiff(String token, String playlistId, List<YouTubePlaylistItem> current, List<String> videoIds, QuotaScope scope) {
        List<PlaylistDiff.Item> items = current.stream().map(i -> new PlaylistDiff.Item(i.id(), i.videoId())).toList();
        List<PlaylistDiff.Op> ops = PlaylistDiff.plan(items, videoIds);
        if (ops.isEmpty()) {
            log.info("[재생목록 항목 변경 없음] playlistId={}, videos={}", playlistId, videoIds.size());
            return ItemSync.ok(List.of(), 0);
        }
        if (!PlaylistDiff.reachesTarget(items, videoIds, ops)) {
            log.error("[재생목록 diff 검증 실패 - 호출 안 함] playlistId={}, current={}, target={}, ops={}", playlistId, items.size(), videoIds.size(), ops);
            return new ItemSync(Outcome.DIFF_INVALID, List.of(), 0, "DIFF_INVALID ops=" + ops.size());
        }
        // 1. 쓰기 합계를 한 번에 예약. 실패하면(예산 부족) 하나도 쓰지 않는다 — 중간에 끊긴 재생목록을 만들지 않기 위함
        QuotaReservation reservation = quota.reserve(scope, ops.stream().map(YouTubePlaylistAdapter::operation).toList());
        PlaylistDiff.Simulation simulation = new PlaylistDiff.Simulation(items, videoIds);
        List<String> skipped = new ArrayList<>();
        int writes = 0;
        try {
            for (int i = 0; i < ops.size(); i++) {
                int index = i;
                PlaylistDiff.Op op = ops.get(i);
                switch (op) {
                    case PlaylistDiff.Delete delete -> {
                        execute(reservation, index, () -> client.deleteItem(token, delete.itemId()));
                        simulation.apply(op, null);
                    }
                    case PlaylistDiff.Move move -> {
                        int position = simulation.position(op);
                        execute(reservation, index, () -> client.moveItem(token, playlistId, move.itemId(), move.videoId(), position));
                        simulation.apply(op, null);
                    }
                    case PlaylistDiff.Insert insert -> {
                        int position = simulation.position(op);
                        try {
                            String itemId = untilVisible(playlistId, attempt -> attempt == 0
                                    ? execute(reservation, index, () -> client.insertItem(token, playlistId, insert.videoId(), position))
                                    : metered(scope, QuotaOperation.INSERT, () -> client.insertItem(token, playlistId, insert.videoId(), position)),
                                    id -> true);
                            simulation.apply(op, itemId);
                        } catch (HttpClientErrorException.NotFound e) {
                            if (!hasReason(e, VIDEO_NOT_FOUND)) {
                                throw e;
                            }
                            log.warn("[재생목록 항목 건너뜀 - 유튜브에 없는 영상] playlistId={}, videoId={}", playlistId, insert.videoId());
                            skipped.add(insert.videoId());
                        }
                    }
                }
                writes++;
            }
        } finally {
            quota.release(reservation);
        }
        // 2. 다시 읽어 비교. 건너뛴 영상은 목표에서 빠진다
        List<String> expected = simulation.videoIds();
        for (int retry = 0; ; retry++) {
            List<String> actual = listAll(token, playlistId, scope).stream().map(YouTubePlaylistItem::videoId).toList();
            if (actual.equals(expected)) {
                log.info("[재생목록 항목 diff 반영] playlistId={}, writes={}, skipped={}", playlistId, writes, skipped.size());
                return ItemSync.ok(List.copyOf(skipped), writes);
            }
            if (retry == PROPAGATION_RETRIES) {
                log.warn("[재생목록 diff 반영 후 불일치] playlistId={}, expected={}, actual={}", playlistId, expected, actual);
                return new ItemSync(Outcome.PARTIAL, List.copyOf(skipped), writes,
                        "PARTIAL expected=" + expected.size() + " actual=" + actual.size());
            }
            long waitMillis = PROPAGATION_BACKOFF_MILLIS << retry;
            log.warn("[재생목록 diff 반영 확인 대기 후 재조회] playlistId={}, retry={}, waitMillis={}", playlistId, retry + 1, waitMillis);
            sleeper.accept(waitMillis);
        }
    }

    // jan.youtube.diff-sync.enabled=false: 현재 항목이 같은 순서면 아무것도 하지 않고, 다르면 전부 지우고 순서대로 넣는다 (수정 전 방식)
    // 한계: 건너뛴 영상 ID 가 곡 마스터에 남아 있는 동안은 현재 항목이 요청과 같아질 수 없어, 동기화마다 전부 지우고 다시 넣는다
    private ItemSync rebuild(String token, String playlistId, List<YouTubePlaylistItem> current, List<String> videoIds, QuotaScope scope) {
        if (current.stream().map(YouTubePlaylistItem::videoId).toList().equals(videoIds)) {
            log.info("[재생목록 항목 변경 없음] playlistId={}, videos={}", playlistId, videoIds.size());
            return ItemSync.ok(List.of(), 0);
        }
        List<QuotaOperation> operations = new ArrayList<>(Collections.nCopies(current.size(), QuotaOperation.DELETE));
        operations.addAll(Collections.nCopies(videoIds.size(), QuotaOperation.INSERT));
        QuotaReservation reservation = quota.reserve(scope, operations);
        List<String> skipped = new ArrayList<>();
        try {
            for (int i = 0; i < current.size(); i++) {
                String itemId = current.get(i).id();
                execute(reservation, i, () -> client.deleteItem(token, itemId));
            }
            for (int i = 0; i < videoIds.size(); i++) {
                int index = current.size() + i;
                String videoId = videoIds.get(i);
                try {
                    untilVisible(playlistId, attempt -> attempt == 0
                            ? execute(reservation, index, () -> client.insertItem(token, playlistId, videoId, null))
                            : metered(scope, QuotaOperation.INSERT, () -> client.insertItem(token, playlistId, videoId, null)), id -> true);
                } catch (HttpClientErrorException.NotFound e) {
                    // playlistItems.insert 문서의 오류 중 영상 하나에 국한된 사유는 videoNotFound 뿐이다(지워졌거나 볼 수 없는 영상, 잘못된 ID).
                    // 그 영상만 빼고 이어간다. 403(권한·쿼터)·5xx·재시도를 다 쓴 playlistNotFound 는 재생목록 전체 문제라 그대로 실패시킨다
                    if (!hasReason(e, VIDEO_NOT_FOUND)) {
                        throw e;
                    }
                    log.warn("[재생목록 항목 건너뜀 - 유튜브에 없는 영상] playlistId={}, videoId={}", playlistId, videoId);
                    skipped.add(videoId);
                }
            }
        } finally {
            quota.release(reservation);
        }
        log.info("[재생목록 항목 교체] playlistId={}, removed={}, added={}, skipped={}",
                playlistId, current.size(), videoIds.size() - skipped.size(), skipped.size());
        return ItemSync.ok(List.copyOf(skipped), current.size() + videoIds.size());
    }

    // 전체 항목. 페이지(호출)마다 1 단위씩 예약한다
    private List<YouTubePlaylistItem> listAll(String token, String playlistId, QuotaScope scope) {
        List<YouTubePlaylistItem> all = new ArrayList<>();
        String pageToken = null;
        do {
            String requested = pageToken;
            YouTubeClient.ItemPage page = metered(scope, QuotaOperation.LIST, () -> client.listItemsPage(token, playlistId, requested));
            all.addAll(page.items());
            pageToken = page.nextPageToken();
        } while (pageToken != null && !pageToken.isBlank());
        return all;
    }

    // visible 이 거짓인 결과나 404 playlistNotFound 면 늘려 가며 기다린 뒤 다시 부른다(attempt 0 이 첫 호출). 다른 404(videoNotFound 등)는 바로 던진다
    private <T> T untilVisible(String playlistId, IntFunction<T> request, Predicate<T> visible) {
        for (int retry = 0; ; retry++) {
            try {
                T result = request.apply(retry);
                if (visible.test(result) || retry == PROPAGATION_RETRIES) {
                    return result;
                }
            } catch (HttpClientErrorException.NotFound e) {
                if (retry == PROPAGATION_RETRIES || !hasReason(e, PLAYLIST_NOT_FOUND)) {
                    throw e;
                }
            }
            long waitMillis = PROPAGATION_BACKOFF_MILLIS << retry;
            log.warn("[유튜브 재생목록 전파 대기 후 재시도] playlistId={}, retry={}, waitMillis={}", playlistId, retry + 1, waitMillis);
            sleeper.accept(waitMillis);
        }
    }

    // 호출 하나를 따로 예약하고 부른다
    private <T> T metered(QuotaScope scope, QuotaOperation operation, Supplier<T> request) {
        QuotaReservation reservation = quota.reserve(scope, List.of(operation));
        try {
            return execute(reservation, 0, request);
        } finally {
            quota.release(reservation);
        }
    }

    private void execute(QuotaReservation reservation, int index, Runnable request) {
        execute(reservation, index, () -> {
            request.run();
            return null;
        });
    }

    // 예약한 index 번째 호출. 응답을 받으면 성공·실패와 관계없이 CONSUMED, 결과를 모르는 I/O 오류(타임아웃 등)는 UNKNOWN.
    // quotaExceeded 면 그날 모든 bucket 을 막고(알림 1회) 429 로 멈춘다 — 재시도하지 않는다
    private <T> T execute(QuotaReservation reservation, int index, Supplier<T> request) {
        T result;
        try {
            result = request.get();
        } catch (RestClientResponseException e) {
            quota.settle(reservation, index, true);
            if (e instanceof HttpClientErrorException.Forbidden forbidden && hasReason(forbidden, QUOTA_EXCEEDED)) {
                quota.exhaust(reservation.date());
                throw new ApplicationException(ErrorCode.YOUTUBE_QUOTA_EXHAUSTED, e);
            }
            throw e;
        } catch (ResourceAccessException e) {
            quota.settle(reservation, index, false);
            throw e;
        }
        quota.settle(reservation, index, true);
        return result;
    }

    private static QuotaOperation operation(PlaylistDiff.Op op) {
        return switch (op) {
            case PlaylistDiff.Delete delete -> QuotaOperation.DELETE;
            case PlaylistDiff.Move move -> QuotaOperation.UPDATE;
            case PlaylistDiff.Insert insert -> QuotaOperation.INSERT;
        };
    }

    // 오류 본문의 error.errors[].reason 값. 본문 전체에서 사유 이름을 찾는다
    private static boolean hasReason(HttpClientErrorException e, String reason) {
        return e.getResponseBodyAsString().contains(reason);
    }

    private String accessToken() {
        YouTubeCredential credential = credentials.findById(YouTubeCredential.SINGLETON_ID)
                .orElseThrow(() -> new ConflictException(ErrorCode.YOUTUBE_NOT_CONNECTED));
        String token;
        try {
            token = client.refreshAccessToken(credential.getRefreshToken()).accessToken();
        } catch (HttpClientErrorException.BadRequest e) {
            if (hasReason(e, INVALID_GRANT)) {
                throw new ApplicationException(ErrorCode.YOUTUBE_RECONNECT_REQUIRED, e);
            }
            throw e;
        }
        if (token == null || token.isBlank()) {
            throw new ApplicationException(ErrorCode.YOUTUBE_API_ERROR, "유튜브 액세스 토큰을 받지 못했습니다.");
        }
        return token;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationException(ErrorCode.YOUTUBE_API_ERROR, e);
        }
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientException e) {
            throw new ApplicationException(ErrorCode.YOUTUBE_API_ERROR, e);
        }
    }
}
