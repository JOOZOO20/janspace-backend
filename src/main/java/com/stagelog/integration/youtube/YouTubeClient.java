package com.stagelog.integration.youtube;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 쿼터: 목록 조회 1, 삽입·수정·삭제·생성 50 단위 (하루 1만). Data API·토큰 주소는 설정(stagelog.youtube.api-base-uri, token-uri)으로 바꿀 수 있다.
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

    static final String DATA_API_BASE = "https://www.googleapis.com/youtube/v3";

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUrl;
    private final String playlistsUrl;
    private final String playlistItemsUrl;

    public YouTubeClient(RestClient.Builder builder, String clientId, String clientSecret) {
        this(builder, clientId, clientSecret, DATA_API_BASE, TOKEN_URL);
    }

    @Autowired
    public YouTubeClient(RestClient.Builder builder,
                         @Value("${stagelog.youtube.client-id:}") String clientId,
                         @Value("${stagelog.youtube.client-secret:}") String clientSecret,
                         @Value("${stagelog.youtube.api-base-uri:" + DATA_API_BASE + "}") String apiBaseUri,
                         @Value("${stagelog.youtube.token-uri:" + TOKEN_URL + "}") String tokenUri) {
        this.restClient = builder.build();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.tokenUrl = tokenUri;
        this.playlistsUrl = apiBaseUri + "/playlists";
        this.playlistItemsUrl = apiBaseUri + "/playlistItems";
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

    // playlistItems.list 한 페이지. nextPageToken 이 비면 마지막 페이지
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemPage(String nextPageToken, List<YouTubePlaylistItem> items) {
    }

    // 만들어진 재생목록 ID
    public String createPlaylist(String accessToken, String title, String description) throws RestClientException {
        Playlist created = restClient.post().uri(playlistsUrl + "?part=snippet,status")
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
        PlaylistListResponse res = restClient.get().uri(playlistsUrl + "?part=snippet&id={id}", playlistId)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(PlaylistListResponse.class);
        if (res == null || res.items() == null || res.items().isEmpty() || res.items().get(0).snippet() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(res.items().get(0).snippet().title());
    }

    // 한 페이지(최대 50개). 호출 한 번이 목록 조회 1 단위라 페이지 순회는 호출자(Quota 를 세는 어댑터)가 한다. pageToken null = 첫 페이지
    public ItemPage listItemsPage(String accessToken, String playlistId, String pageToken) throws RestClientException {
        ItemPage page = restClient.get()
                .uri(playlistItemsUrl + "?part=snippet&playlistId={id}&maxResults={n}&pageToken={t}",
                        playlistId, PAGE_SIZE, pageToken == null ? "" : pageToken)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(ItemPage.class);
        return page == null ? new ItemPage(null, List.of()) : new ItemPage(page.nextPageToken(), page.items() == null ? List.of() : page.items());
    }

    // position 은 결과 목록에서의 0 기반 위치, null 이면 끝에 붙는다. 만들어진 playlistItemId 를 돌려준다
    public String insertItem(String accessToken, String playlistId, String videoId, Integer position) throws RestClientException {
        Map<String, Object> snippet = new HashMap<>();
        snippet.put("playlistId", playlistId);
        snippet.put("resourceId", Map.of("kind", "youtube#video", "videoId", videoId));
        if (position != null) {
            snippet.put("position", position);
        }
        YouTubePlaylistItem created = restClient.post().uri(playlistItemsUrl + "?part=snippet")
                .headers(h -> h.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("snippet", snippet))
                .retrieve()
                .body(YouTubePlaylistItem.class);
        return created == null ? null : created.id();
    }

    // playlistItems.update 로 위치만 옮긴다. 요청에 없는 snippet 속성은 지워지므로 playlistId·resourceId 를 함께 보낸다(문서상 필수)
    public void moveItem(String accessToken, String playlistId, String itemId, String videoId, int position) throws RestClientException {
        restClient.put().uri(playlistItemsUrl + "?part=snippet")
                .headers(h -> h.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("id", itemId, "snippet", Map.of("playlistId", playlistId,
                        "resourceId", Map.of("kind", "youtube#video", "videoId", videoId), "position", position)))
                .retrieve()
                .toBodilessEntity();
    }

    public void deleteItem(String accessToken, String itemId) throws RestClientException {
        restClient.delete().uri(playlistItemsUrl + "?id={id}", itemId)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .toBodilessEntity();
    }

    private YouTubeTokenResponse token(MultiValueMap<String, String> form) {
        return restClient.post().uri(tokenUrl)
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
