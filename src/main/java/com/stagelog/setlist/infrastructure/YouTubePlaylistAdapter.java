package com.stagelog.setlist.infrastructure;

import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.ConflictException;
import com.stagelog.integration.youtube.YouTubeClient;
import com.stagelog.integration.youtube.YouTubePlaylistItem;
import com.stagelog.setlist.application.port.PlaylistPort;
import com.stagelog.setlist.domain.YouTubeCredential;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * PlaylistPort 의 YouTube Data API 구현. 호출마다 DB 의 리프레시 토큰으로 액세스 토큰을 새로 받는다.
 * 한계: 액세스 토큰(1시간)을 캐시하지 않는다. 행사당 하루 몇 번 수준이라 토큰 요청 비용이 무시할 만하다. 잦아지면 만료 시각과 함께 메모리에 둔다
 */
@Slf4j
@Component
public class YouTubePlaylistAdapter implements PlaylistPort {

    // 갓 만든 재생목록은 몇 초 동안 다른 API 에 안 보인다(항목 추가·조회가 404 playlistNotFound, 재생목록 조회가 빈 결과).
    // 1·2·4·8초를 기다리며 다시 부르고, 그래도 안 보이면 그 결과(예외 또는 빈 결과)를 그대로 낸다
    static final int PROPAGATION_RETRIES = 4;
    static final long PROPAGATION_BACKOFF_MILLIS = 1_000;
    // playlistItems.insert 404 오류 본문의 reason
    static final String PLAYLIST_NOT_FOUND = "playlistNotFound";
    static final String VIDEO_NOT_FOUND = "videoNotFound";
    // 토큰 갱신 400 오류 본문의 error. 리프레시 토큰이 폐기·만료됐다는 뜻이라 다시 연결해야 한다
    static final String INVALID_GRANT = "invalid_grant";

    private final YouTubeClient client;
    private final YouTubeCredentialRepository credentials;
    private final LongConsumer sleeper;

    @Autowired
    public YouTubePlaylistAdapter(YouTubeClient client, YouTubeCredentialRepository credentials) {
        this(client, credentials, YouTubePlaylistAdapter::sleep);
    }

    // 테스트는 sleeper 를 바꿔 실제로 기다리지 않는다
    public YouTubePlaylistAdapter(YouTubeClient client, YouTubeCredentialRepository credentials, LongConsumer sleeper) {
        this.client = client;
        this.credentials = credentials;
        this.sleeper = sleeper;
    }

    @Override
    public String create(String title, String description) {
        return call(() -> client.createPlaylist(accessToken(), title, description));
    }

    // 현재 항목이 같은 순서면 아무것도 하지 않고, 다르면 전부 지우고 순서대로 넣는다 (부분 이동보다 단순하고 결정적)
    // 한계: 건너뛴 영상 ID 가 곡 마스터에 남아 있는 동안은 현재 항목이 요청과 같아질 수 없어, 동기화마다 전부 지우고 다시 넣는다
    // (항목 N개면 삭제·추가 N번씩, 각 50 단위). 동기화는 셋리 변경·관리자 요청 때만 돌고 관리자가 영상 ID 를 고치면 끝나므로 감수한다
    @Override
    public List<String> replaceItems(String playlistId, List<String> videoIds) {
        return call(() -> {
            String token = accessToken();
            List<YouTubePlaylistItem> current = untilVisible(playlistId, () -> client.listItems(token, playlistId), items -> true);
            if (current.stream().map(YouTubePlaylistItem::videoId).toList().equals(videoIds)) {
                log.info("[재생목록 항목 변경 없음] playlistId={}, videos={}", playlistId, videoIds.size());
                return List.of();
            }
            for (YouTubePlaylistItem item : current) {
                client.deleteItem(token, item.id());
            }
            List<String> skipped = new ArrayList<>();
            for (String videoId : videoIds) {
                try {
                    untilVisible(playlistId, () -> {
                        client.insertItem(token, playlistId, videoId);
                        return videoId;
                    }, inserted -> true);
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
            log.info("[재생목록 항목 교체] playlistId={}, removed={}, added={}, skipped={}",
                    playlistId, current.size(), videoIds.size() - skipped.size(), skipped.size());
            return List.copyOf(skipped);
        });
    }

    @Override
    public Optional<String> currentTitle(String playlistId) {
        return call(() -> {
            String token = accessToken();
            return untilVisible(playlistId, () -> client.playlistTitle(token, playlistId), Optional::isPresent);
        });
    }

    // visible 이 거짓인 결과나 404 playlistNotFound 면 늘려 가며 기다린 뒤 다시 부른다. 다른 404(videoNotFound 등)는 바로 던진다
    private <T> T untilVisible(String playlistId, Supplier<T> request, Predicate<T> visible) {
        for (int retry = 0; ; retry++) {
            try {
                T result = request.get();
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
