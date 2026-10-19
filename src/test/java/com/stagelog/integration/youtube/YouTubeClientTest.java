package com.stagelog.integration.youtube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.setlist.domain.YouTubeCredential;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import com.stagelog.setlist.infrastructure.YouTubeOAuthAdapter;
import com.stagelog.setlist.infrastructure.YouTubePlaylistAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/**
 * YouTube Data API·구글 OAuth 픽스처 매핑 (실키 없음 — 실연동 미확인).
 */
class YouTubeClientTest {

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    YouTubeClient client = new YouTubeClient(builder, "cid", "csec");
    // 어댑터가 재시도 전에 기다리려 한 시간(ms). 실제로는 기다리지 않는다
    List<Long> waits = new ArrayList<>();

    @Test
    @DisplayName("인가 URL: offline + consent + youtube scope + redirect_uri 인코딩")
    void authorize_url() {
        String url = new YouTubeOAuthAdapter(client, "https://api.stagelog.test/").authorizeUrl("st4te");

        assertThat(url).startsWith(YouTubeClient.AUTHORIZE_URL + "?response_type=code&client_id=cid")
                .contains("redirect_uri=https%3A%2F%2Fapi.stagelog.test%2Fapi%2Fv1%2Fadmin%2Fyoutube%2Fcallback")
                .contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fyoutube")
                .contains("access_type=offline").contains("prompt=consent").endsWith("state=st4te");
    }

    @Test
    @DisplayName("코드 교환: form POST, refresh_token 을 돌려준다")
    void exchange_code() {
        server.expect(requestTo(YouTubeClient.TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(Matchers.containsString("grant_type=authorization_code")))
                .andExpect(content().string(Matchers.containsString("code=c0de")))
                .andExpect(content().string(Matchers.containsString("client_secret=csec")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/youtube/token.json"), MediaType.APPLICATION_JSON));

        String refresh = new YouTubeOAuthAdapter(client, "http://localhost:8080").exchangeCode("c0de");

        assertThat(refresh).isEqualTo("1//0gExampleRefreshTokenValue");
        server.verify();
    }

    @Test
    @DisplayName("재생목록 생성: 리프레시 토큰으로 액세스 토큰을 받고 snippet/status 를 POST 해 id 를 돌려준다")
    void create_playlist() {
        expectRefresh();
        server.expect(requestTo(YouTubeClient.PLAYLISTS_URL + "?part=snippet,status"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer ya29.a0AfB_byC-refreshed-access-token"))
                .andExpect(jsonPath("$.snippet.title").value("잔나비 2026-10-23 KSPO DOME 셋리"))
                .andExpect(jsonPath("$.status.privacyStatus").value("public"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/youtube/playlist-insert.json"), MediaType.APPLICATION_JSON));

        String id = adapter().create("잔나비 2026-10-23 KSPO DOME 셋리", "desc");

        assertThat(id).isEqualTo("PLabc123def456");
        server.verify();
    }

    @Test
    @DisplayName("항목 교체: 페이지를 끝까지 읽고, 순서가 다르면 전부 지운 뒤 순서대로 넣는다")
    void replace_items_reads_all_pages_then_rewrites() {
        expectRefresh();
        server.expect(requestTo(Matchers.startsWith(YouTubeClient.PLAYLIST_ITEMS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("playlistId", "PLabc123def456"))
                .andExpect(queryParam("maxResults", "50"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/youtube/playlist-items-page1.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(YouTubeClient.PLAYLIST_ITEMS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("pageToken", "CDIQAA"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/youtube/playlist-items-page2.json"), MediaType.APPLICATION_JSON));
        for (String itemId : List.of("UExhYmMxMjMuMTIzNDU2Nzg5MDEyMzQ1Njc4OQ", "UExhYmMxMjMuMjIzNDU2Nzg5MDEyMzQ1Njc4OQ", "UExhYmMxMjMuMzIzNDU2Nzg5MDEyMzQ1Njc4OQ")) {
            server.expect(requestTo(YouTubeClient.PLAYLIST_ITEMS_URL + "?id=" + itemId))
                    .andExpect(method(HttpMethod.DELETE))
                    .andRespond(withNoContent());
        }
        for (String videoId : List.of("vidLegend03", "vidLovers01")) {
            server.expect(requestTo(YouTubeClient.PLAYLIST_ITEMS_URL + "?part=snippet"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.snippet.playlistId").value("PLabc123def456"))
                    .andExpect(jsonPath("$.snippet.resourceId.videoId").value(videoId))
                    .andRespond(withSuccess("{\"id\":\"new\"}", MediaType.APPLICATION_JSON));
        }

        adapter().replaceItems("PLabc123def456", List.of("vidLegend03", "vidLovers01"));

        server.verify();
    }

    @Test
    @DisplayName("항목 교체: 현재 순서와 같으면 삭제·삽입 없음")
    void replace_items_noop_when_same() {
        expectRefresh();
        server.expect(requestTo(Matchers.startsWith(YouTubeClient.PLAYLIST_ITEMS_URL)))
                .andRespond(withSuccess(new ClassPathResource("fixtures/youtube/playlist-items-page2.json"), MediaType.APPLICATION_JSON));

        adapter().replaceItems("PLabc123def456", List.of("vidLegend03"));

        server.verify();
    }

    @Test
    @DisplayName("재생목록 제목 조회: items 가 있으면 제목, 1·2·4·8초 간격으로 다시 봐도 비어 있으면 empty(지워짐). 5xx 는 502 YOUTUBE_API_ERROR")
    void current_title_and_errors() {
        expectRefresh();
        server.expect(requestTo(YouTubeClient.PLAYLISTS_URL + "?part=snippet&id=PLabc123def456"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/youtube/playlists-list.json"), MediaType.APPLICATION_JSON));
        expectRefresh();
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo(YouTubeClient.PLAYLISTS_URL + "?part=snippet&id=gone"))
                    .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        }
        expectRefresh();
        server.expect(requestTo(YouTubeClient.PLAYLISTS_URL + "?part=snippet&id=boom")).andRespond(withServerError());
        YouTubePlaylistAdapter adapter = adapter();

        assertThat(adapter.currentTitle("PLabc123def456")).contains("잔나비 2026-10-23 KSPO DOME 셋리");
        assertThat(adapter.currentTitle("gone")).isEmpty();
        assertThat(waits).containsExactly(1_000L, 2_000L, 4_000L, 8_000L);
        assertThatThrownBy(() -> adapter.currentTitle("boom")).isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode()).isEqualTo(ErrorCode.YOUTUBE_API_ERROR);
        server.verify();
    }

    @Test
    @DisplayName("토큰 갱신이 400 invalid_grant(리프레시 토큰 폐기·만료)면 502 가 아니라 409 YOUTUBE_RECONNECT_REQUIRED")
    void revoked_refresh_token() {
        server.expect(requestTo(YouTubeClient.TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": \"invalid_grant\", \"error_description\": \"Token has been expired or revoked.\"}"));

        assertThatThrownBy(() -> adapter().currentTitle("PLabc123def456")).isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode()).isEqualTo(ErrorCode.YOUTUBE_RECONNECT_REQUIRED);
        server.verify();
    }

    @Test
    @DisplayName("갓 만든 재생목록에 항목 추가가 404 playlistNotFound 면 1초 기다려 같은 항목을 다시 넣고 이어간다")
    void insert_retries_playlist_not_found() {
        expectRefresh();
        server.expect(requestTo(Matchers.startsWith(YouTubeClient.PLAYLIST_ITEMS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(YouTubeClient.PLAYLIST_ITEMS_URL + "?part=snippet"))
                .andExpect(jsonPath("$.snippet.resourceId.videoId").value("vidLegend03"))
                .andRespond(notFound("playlistNotFound"));
        for (String videoId : List.of("vidLegend03", "vidLovers01")) {
            server.expect(requestTo(YouTubeClient.PLAYLIST_ITEMS_URL + "?part=snippet"))
                    .andExpect(jsonPath("$.snippet.resourceId.videoId").value(videoId))
                    .andRespond(withSuccess("{\"id\":\"new\"}", MediaType.APPLICATION_JSON));
        }

        adapter().replaceItems("PLnew", List.of("vidLegend03", "vidLovers01"));

        assertThat(waits).containsExactly(1_000L);
        server.verify();
    }

    @Test
    @DisplayName("3곡 중 2번째가 404 videoNotFound 면 그 영상만 건너뛰고 1·3번째를 순서대로 넣으며, 건너뛴 영상 ID 를 돌려준다(재시도 없음)")
    void insert_skips_video_not_found() {
        expectRefresh();
        server.expect(requestTo(Matchers.startsWith(YouTubeClient.PLAYLIST_ITEMS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        expectInsert("vidLovers01", withSuccess("{\"id\":\"new\"}", MediaType.APPLICATION_JSON));
        expectInsert("vidGone", error(HttpStatus.NOT_FOUND, "videoNotFound"));
        expectInsert("vidLegend03", withSuccess("{\"id\":\"new\"}", MediaType.APPLICATION_JSON));

        List<String> skipped = adapter().replaceItems("PLabc123def456", List.of("vidLovers01", "vidGone", "vidLegend03"));

        assertThat(skipped).containsExactly("vidGone");
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("모든 영상이 videoNotFound 면 기존 항목은 지운 채 빈 재생목록으로 끝나고 전부 돌려준다")
    void insert_all_video_not_found() {
        expectRefresh();
        server.expect(requestTo(Matchers.startsWith(YouTubeClient.PLAYLIST_ITEMS_URL)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new ClassPathResource("fixtures/youtube/playlist-items-page2.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(YouTubeClient.PLAYLIST_ITEMS_URL + "?id=UExhYmMxMjMuMzIzNDU2Nzg5MDEyMzQ1Njc4OQ"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());
        expectInsert("vidGone1", error(HttpStatus.NOT_FOUND, "videoNotFound"));
        expectInsert("vidGone2", error(HttpStatus.NOT_FOUND, "videoNotFound"));

        List<String> skipped = adapter().replaceItems("PLabc123def456", List.of("vidGone1", "vidGone2"));

        assertThat(skipped).containsExactly("vidGone1", "vidGone2");
        server.verify();
    }

    @Test
    @DisplayName("403(쿼터 초과·권한 없음)·사유가 다른 404·5xx 는 건너뛰지 않고 바로 502, playlistNotFound 는 네 번 다시 시도한 뒤 502")
    void insert_gives_up() {
        List<ResponseCreator> failures = List.of(error(HttpStatus.FORBIDDEN, "quotaExceeded"),
                error(HttpStatus.FORBIDDEN, "playlistItemsNotAccessible"), error(HttpStatus.NOT_FOUND, "notFound"), withServerError());
        for (ResponseCreator failure : failures) {
            expectRefresh();
            server.expect(requestTo(Matchers.startsWith(YouTubeClient.PLAYLIST_ITEMS_URL))).andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
            // 첫 영상에서 멈추므로 두 번째 영상 요청은 없다
            expectInsert("vidLovers01", failure);
        }
        expectRefresh();
        server.expect(requestTo(Matchers.startsWith(YouTubeClient.PLAYLIST_ITEMS_URL))).andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo(YouTubeClient.PLAYLIST_ITEMS_URL + "?part=snippet")).andRespond(notFound("playlistNotFound"));
        }
        YouTubePlaylistAdapter adapter = adapter();

        for (int i = 0; i < failures.size(); i++) {
            assertThatThrownBy(() -> adapter.replaceItems("PLnew", List.of("vidLovers01", "vidLegend03"))).isInstanceOf(ApplicationException.class)
                    .extracting(e -> ((ApplicationException) e).getErrorCode()).isEqualTo(ErrorCode.YOUTUBE_API_ERROR);
        }
        assertThat(waits).isEmpty();
        assertThatThrownBy(() -> adapter.replaceItems("PLnew", List.of("vidLegend03"))).isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode()).isEqualTo(ErrorCode.YOUTUBE_API_ERROR);
        assertThat(waits).containsExactly(1_000L, 2_000L, 4_000L, 8_000L);
        server.verify();
    }

    @Test
    @DisplayName("자격 증명 행이 없으면 409 YOUTUBE_NOT_CONNECTED (요청 없음)")
    void not_connected() {
        YouTubeCredentialRepository repo = mock(YouTubeCredentialRepository.class);
        when(repo.findById(YouTubeCredential.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new YouTubePlaylistAdapter(client, repo).create("t", "d"))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode()).isEqualTo(ErrorCode.YOUTUBE_NOT_CONNECTED);
        server.verify();
    }

    private void expectRefresh() {
        server.expect(requestTo(YouTubeClient.TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(Matchers.containsString("grant_type=refresh_token")))
                .andExpect(content().string(Matchers.containsString("refresh_token=1%2F%2F0gExampleRefreshTokenValue")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/youtube/token-refresh.json"), MediaType.APPLICATION_JSON));
    }

    private YouTubePlaylistAdapter adapter() {
        YouTubeCredentialRepository repo = mock(YouTubeCredentialRepository.class);
        when(repo.findById(YouTubeCredential.SINGLETON_ID)).thenReturn(Optional.of(new YouTubeCredential("1//0gExampleRefreshTokenValue")));
        return new YouTubePlaylistAdapter(client, repo, waits::add);
    }

    private void expectInsert(String videoId, ResponseCreator response) {
        server.expect(requestTo(YouTubeClient.PLAYLIST_ITEMS_URL + "?part=snippet"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.snippet.resourceId.videoId").value(videoId))
                .andRespond(response);
    }

    private static ResponseCreator notFound(String reason) {
        return error(HttpStatus.NOT_FOUND, reason);
    }

    // YouTube Data API 오류 본문 모양: error.errors[].reason
    private static ResponseCreator error(HttpStatus status, String reason) {
        return withStatus(status).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":" + status.value() + ",\"errors\":[{\"domain\":\"youtube.playlistItem\",\"reason\":\"" + reason + "\"}]}}");
    }
}
