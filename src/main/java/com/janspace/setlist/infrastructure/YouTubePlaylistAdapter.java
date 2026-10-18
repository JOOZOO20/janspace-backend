package com.janspace.setlist.infrastructure;

import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.integration.youtube.YouTubeClient;
import com.janspace.integration.youtube.YouTubePlaylistItem;
import com.janspace.setlist.application.port.PlaylistPort;
import com.janspace.setlist.domain.YouTubeCredential;
import com.janspace.setlist.domain.YouTubeCredentialRepository;
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
    @Override
    public void replaceItems(String playlistId, List<String> videoIds) {
        call(() -> {
            String token = accessToken();
            List<YouTubePlaylistItem> current = untilVisible(playlistId, () -> client.listItems(token, playlistId), items -> true);
            if (current.stream().map(YouTubePlaylistItem::videoId).toList().equals(videoIds)) {
                log.info("[재생목록 항목 변경 없음] playlistId={}, videos={}", playlistId, videoIds.size());
                return null;
            }
            for (YouTubePlaylistItem item : current) {
                client.deleteItem(token, item.id());
            }
            for (String videoId : videoIds) {
                untilVisible(playlistId, () -> {
                    client.insertItem(token, playlistId, videoId);
                    return videoId;
                }, inserted -> true);
            }
            log.info("[재생목록 항목 교체] playlistId={}, removed={}, added={}", playlistId, current.size(), videoIds.size());
            return null;
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
                if (retry == PROPAGATION_RETRIES || !e.getResponseBodyAsString().contains("playlistNotFound")) {
                    throw e;
                }
            }
            long waitMillis = PROPAGATION_BACKOFF_MILLIS << retry;
            log.warn("[유튜브 재생목록 전파 대기 후 재시도] playlistId={}, retry={}, waitMillis={}", playlistId, retry + 1, waitMillis);
            sleeper.accept(waitMillis);
        }
    }

    private String accessToken() {
        YouTubeCredential credential = credentials.findById(YouTubeCredential.SINGLETON_ID)
                .orElseThrow(() -> new ConflictException(ErrorCode.YOUTUBE_NOT_CONNECTED));
        String token = client.refreshAccessToken(credential.getRefreshToken()).accessToken();
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
