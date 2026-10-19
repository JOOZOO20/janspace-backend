package com.stagelog.integration.youtube;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * YouTube Data API v3 재생목록 클라이언트 + 구글 OAuth(인가 URL, 코드 교환, 리프레시). 4xx/5xx 는 RestClientResponseException.
 * 쿼터: 목록 조회 1, 삽입·삭제·생성 50 단위 (하루 1만).
 */
@Slf4j
@Component
public class YouTubeClient {

    public static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    public static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    public static final String PLAYLISTS_URL = "https://www.googleapis.com/youtube/v3/playlists";
    public static final String PLAYLIST_ITEMS_URL = "https://www.googleapis.com/youtube/v3/playlistItems";
    static final String SCOPE = "https://www.googleapis.com/auth/youtube";
    static final int PAGE_SIZE = 50;

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public YouTubeClient(RestClient.Builder builder,
                         @Value("${stagelog.youtube.client-id:}") String clientId,
                         @Value("${stagelog.youtube.client-secret:}") String clientSecret) {
        this.restClient = builder.build();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    // offline + consent 여야 리프레시 토큰이 매번 온다
    public String authorizeUrl(String redirectUri, String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", "{clientId}")
                .queryParam("redirect_uri", "{redirectUri}")
                .queryParam("scope", "{scope}")
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", "{state}")
                .encode().build().expand(clientId, redirectUri, SCOPE, state).toUriString();
    }

    public YouTubeTokenResponse exchangeCode(String code, String redirectUri) throws RestClientException {
        MultiValueMap<String, String> form = credentials();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        log.info("[유튜브 토큰 교환 요청]");
        return token(form);
    }

    public YouTubeTokenResponse refreshAccessToken(String refreshToken) throws RestClientException {
        MultiValueMap<String, String> form = credentials();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return token(form);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Playlist(String id, Snippet snippet) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Snippet(String title) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlaylistListResponse(List<Playlist> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlaylistItemListResponse(String nextPageToken, List<YouTubePlaylistItem> items) {
    }

    // 만들어진 재생목록 ID
    public String createPlaylist(String accessToken, String title, String description) throws RestClientException {
        Playlist created = restClient.post().uri(PLAYLISTS_URL + "?part=snippet,status")
                .headers(h -> h.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("snippet", Map.of("title", title, "description", description),
                        "status", Map.of("privacyStatus", "public")))
                .retrieve()
                .body(Playlist.class);
        log.info("[유튜브 재생목록 생성] id={}, title={}", created == null ? null : created.id(), title);
        return created == null ? null : created.id();
    }

    // 지워진 재생목록은 items 가 빈다
    public Optional<String> playlistTitle(String accessToken, String playlistId) throws RestClientException {
        PlaylistListResponse res = restClient.get().uri(PLAYLISTS_URL + "?part=snippet&id={id}", playlistId)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(PlaylistListResponse.class);
        if (res == null || res.items() == null || res.items().isEmpty() || res.items().get(0).snippet() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(res.items().get(0).snippet().title());
    }

    // 전체 항목 (페이지를 끝까지 따라간다)
    public List<YouTubePlaylistItem> listItems(String accessToken, String playlistId) throws RestClientException {
        List<YouTubePlaylistItem> all = new ArrayList<>();
        String pageToken = null;
        do {
            PlaylistItemListResponse res = restClient.get()
                    .uri(PLAYLIST_ITEMS_URL + "?part=snippet&playlistId={id}&maxResults={n}&pageToken={t}",
                            playlistId, PAGE_SIZE, pageToken == null ? "" : pageToken)
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve()
                    .body(PlaylistItemListResponse.class);
            if (res == null) {
                break;
            }
            if (res.items() != null) {
                all.addAll(res.items());
            }
            pageToken = res.nextPageToken();
        } while (pageToken != null && !pageToken.isBlank());
        return all;
    }

    public void insertItem(String accessToken, String playlistId, String videoId) throws RestClientException {
        restClient.post().uri(PLAYLIST_ITEMS_URL + "?part=snippet")
                .headers(h -> h.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("snippet", Map.of("playlistId", playlistId,
                        "resourceId", Map.of("kind", "youtube#video", "videoId", videoId))))
                .retrieve()
                .toBodilessEntity();
    }

    public void deleteItem(String accessToken, String itemId) throws RestClientException {
        restClient.delete().uri(PLAYLIST_ITEMS_URL + "?id={id}", itemId)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .toBodilessEntity();
    }

    private YouTubeTokenResponse token(MultiValueMap<String, String> form) {
        return restClient.post().uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(YouTubeTokenResponse.class);
    }

    private MultiValueMap<String, String> credentials() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        return form;
    }
}
