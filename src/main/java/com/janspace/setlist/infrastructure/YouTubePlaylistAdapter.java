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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

/**
 * PlaylistPort 의 YouTube Data API 구현. 호출마다 DB 의 리프레시 토큰으로 액세스 토큰을 새로 받는다.
 * 한계: 액세스 토큰(1시간)을 캐시하지 않는다. 행사당 하루 몇 번 수준이라 토큰 요청 비용이 무시할 만하다. 잦아지면 만료 시각과 함께 메모리에 둔다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouTubePlaylistAdapter implements PlaylistPort {

    private final YouTubeClient client;
    private final YouTubeCredentialRepository credentials;

    @Override
    public String create(String title, String description) {
        return call(() -> client.createPlaylist(accessToken(), title, description));
    }

    // 현재 항목이 같은 순서면 아무것도 하지 않고, 다르면 전부 지우고 순서대로 넣는다 (부분 이동보다 단순하고 결정적)
    @Override
    public void replaceItems(String playlistId, List<String> videoIds) {
        call(() -> {
            String token = accessToken();
            List<YouTubePlaylistItem> current = client.listItems(token, playlistId);
            if (current.stream().map(YouTubePlaylistItem::videoId).toList().equals(videoIds)) {
                log.info("[재생목록 항목 변경 없음] playlistId={}, videos={}", playlistId, videoIds.size());
                return null;
            }
            for (YouTubePlaylistItem item : current) {
                client.deleteItem(token, item.id());
            }
            for (String videoId : videoIds) {
                client.insertItem(token, playlistId, videoId);
            }
            log.info("[재생목록 항목 교체] playlistId={}, removed={}, added={}", playlistId, current.size(), videoIds.size());
            return null;
        });
    }

    @Override
    public Optional<String> currentTitle(String playlistId) {
        return call(() -> client.playlistTitle(accessToken(), playlistId));
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

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientException e) {
            throw new ApplicationException(ErrorCode.YOUTUBE_API_ERROR, e);
        }
    }
}
