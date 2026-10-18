package com.janspace.setlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;
import com.janspace.setlist.application.SyncPlaylistUseCase;
import com.janspace.setlist.domain.YouTubeCredential;
import com.janspace.setlist.domain.YouTubeCredentialRepository;
import com.janspace.setlist.support.SetlistIntegrationTest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재생목록: 채널 미연결이면 건너뜀(수동은 409), 연결 뒤 첫 편집에 생성 + 항목, 다음 편집에 같은 재생목록 항목만 갱신. 리스너는 커밋 뒤 비동기.
 */
class PlaylistSyncIntegrationTest extends SetlistIntegrationTest {

    @Autowired
    YouTubeCredentialRepository credentials;
    @Autowired
    SyncPlaylistUseCase syncPlaylist;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    StringRedisTemplate redis;

    Long eventId;
    Long a;
    Long b;
    Long c;
    Long noVideo;

    @BeforeEach
    void setUp() {
        eventId = newEvent("잔나비 단독 공연", today.plusDays(1));
        a = newSong("주저하는 연인들을 위해", null, "vidA");
        b = newSong("악몽", null, "vidB");
        c = newSong("전설", null, "vidC");
        noVideo = newSong("영상 없는 곡", null, null);
    }

    @Test
    @DisplayName("채널 미연결: 편집해도 유튜브를 부르지 않고, 관리자 수동 동기화는 409 YOUTUBE_NOT_CONNECTED")
    void not_connected() throws Exception {
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/events/{id}/setlist/playlist", eventId).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("YOUTUBE_NOT_CONNECTED"));

        verify(playlistPort, never()).create(anyString(), anyString());
        verify(playlistPort, never()).replaceItems(anyString(), any());
    }

    @Test
    @DisplayName("연결 뒤: 첫 편집이 재생목록을 만들고 영상 있는 곡만 넣으며, 두 번째 편집은 같은 재생목록의 항목만 맞춘다. sync_runs(YOUTUBE) 기록")
    void creates_then_reconciles() throws Exception {
        credentials.save(new YouTubeCredential("rt"));
        when(playlistPort.create(anyString(), anyString())).thenReturn("PL1");
        when(playlistPort.currentTitle("PL1")).thenReturn(Optional.of("잔나비 " + today.plusDays(1) + " 셋리"));

        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a, noVideo, b)))
                .andExpect(status().isOk());

        verify(playlistPort, timeout(10_000)).create("잔나비 " + today.plusDays(1) + " 셋리", "잔스페이스 셋리 위키에서 자동으로 만든 재생목록입니다.");
        verify(playlistPort, timeout(10_000)).replaceItems("PL1", List.of("vidA", "vidB"));
        awaitPlaylistId("PL1");

        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(b)))
                .andExpect(status().isOk());

        verify(playlistPort, timeout(10_000)).replaceItems("PL1", List.of("vidB"));
        verify(playlistPort, times(1)).create(anyString(), anyString());
        // 자동 동기화가 잠금을 풀기 전에 수동 동기화를 부르면 409 PLAYLIST_SYNC_IN_PROGRESS 다
        awaitSyncUnlocked();

        // 관리자 수동 동기화도 같은 재생목록. 유튜브에서 지워졌으면(currentTitle empty) 새로 만든다
        mvc.perform(post("/api/v1/admin/events/{id}/setlist/playlist", eventId).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.playlistId").value("PL1"))
                .andExpect(jsonPath("$.data.playlistUrl").value("https://www.youtube.com/playlist?list=PL1"));
        when(playlistPort.currentTitle("PL1")).thenReturn(Optional.empty());
        when(playlistPort.create(anyString(), anyString())).thenReturn("PL2");
        mvc.perform(post("/api/v1/admin/events/{id}/setlist/playlist", eventId).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.playlistId").value("PL2"));
        assertThat(jdbc.queryForObject("SELECT youtube_playlist_id FROM setlist_pages WHERE event_id = ?", String.class, eventId)).isEqualTo("PL2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sync_runs WHERE source = 'YOUTUBE' AND status = 'OK'", Integer.class)).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("항목 맞추기가 실패해도 만든 재생목록 ID 는 저장되고(502), 다음 동기화는 새로 만들지 않고 그 재생목록을 다시 쓴다")
    void keeps_created_playlist_when_items_fail() throws Exception {
        // sync_runs 는 테스트마다 비우지 않으므로 이 테스트가 남긴 행만 본다
        long lastRunId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM sync_runs", Long.class);
        credentials.save(new YouTubeCredential("rt"));
        when(playlistPort.create(anyString(), anyString())).thenReturn("PL1");
        when(playlistPort.currentTitle("PL1")).thenReturn(Optional.of("잔나비 " + today.plusDays(1) + " 셋리"));
        doThrow(new ApplicationException(ErrorCode.YOUTUBE_API_ERROR, "playlistNotFound")).doReturn(List.of())
                .when(playlistPort).replaceItems(eq("PL1"), any());

        mvc.perform(post("/api/v1/admin/events/{id}/setlist/playlist", eventId).with(admin()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("YOUTUBE_API_ERROR"));
        assertThat(jdbc.queryForObject("SELECT youtube_playlist_id FROM setlist_pages WHERE event_id = ?", String.class, eventId)).isEqualTo("PL1");

        mvc.perform(post("/api/v1/admin/events/{id}/setlist/playlist", eventId).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.playlistId").value("PL1"));
        verify(playlistPort, times(1)).create(anyString(), anyString());
        verify(playlistPort, times(2)).replaceItems(eq("PL1"), any());
        assertThat(jdbc.queryForList("SELECT status FROM sync_runs WHERE source = 'YOUTUBE' AND id > ? ORDER BY id", String.class, lastRunId)).containsExactly("FAILED", "OK");
    }

    @Test
    @DisplayName("같은 행사의 수동 동기화 두 개가 동시에 와도 재생목록은 하나만 만들고, 겹친 쪽은 409 PLAYLIST_SYNC_IN_PROGRESS")
    void concurrent_syncs_create_once() throws Exception {
        credentials.save(new YouTubeCredential("rt"));
        jdbc.update("INSERT INTO setlist_pages (event_id, order_confirmed, version, created_at, updated_at) "
                + "VALUES (?, FALSE, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", eventId);
        // 생성이 끝나기 전에 다른 쪽이 확인 단계에 들어오도록 생성 응답을 늦춘다
        when(playlistPort.create(anyString(), anyString())).thenAnswer(invocation -> {
            Thread.sleep(500);
            return "PL1";
        });
        when(playlistPort.currentTitle("PL1")).thenReturn(Optional.of("잔나비 " + today.plusDays(1) + " 셋리"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = pool.invokeAll(List.of(() -> syncPlaylist.syncOrThrow(eventId), () -> syncPlaylist.syncOrThrow(eventId)));
            int succeeded = 0;
            for (Future<String> result : results) {
                try {
                    assertThat(result.get()).isEqualTo("PL1");
                    succeeded++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ApplicationException.class);
                    assertThat(((ApplicationException) e.getCause()).getErrorCode()).isEqualTo(ErrorCode.PLAYLIST_SYNC_IN_PROGRESS);
                }
            }
            assertThat(succeeded).isPositive();
            verify(playlistPort, times(succeeded)).replaceItems("PL1", List.of());
        } finally {
            pool.shutdown();
        }
        verify(playlistPort, times(1)).create(anyString(), anyString());
    }

    @Test
    @DisplayName("동기화 A 가 옛 셋리로 항목을 맞추는 사이 셋리가 바뀌고 동기화 B 가 오면, B 는 겹쳐 돌지 않고 A 가 새 셋리로 한 번 더 맞춘다")
    void overlapping_sync_ends_with_latest_setlist() throws Exception {
        credentials.save(new YouTubeCredential("rt"));
        jdbc.update("INSERT INTO setlist_pages (event_id, order_confirmed, version, youtube_playlist_id, created_at, updated_at) "
                + "VALUES (?, FALSE, 1, 'PL1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", eventId);
        insertItem(a, 1);
        when(playlistPort.currentTitle("PL1")).thenReturn(Optional.of("잔나비 " + today.plusDays(1) + " 셋리"));
        CountDownLatch replacing = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        // 유튜브 쪽 최종 상태 = 마지막으로 끝난 replaceItems 의 항목
        List<List<String>> finished = new CopyOnWriteArrayList<>();
        when(playlistPort.replaceItems(eq("PL1"), any())).thenAnswer(invocation -> {
            List<String> videos = invocation.getArgument(1);
            if (videos.equals(List.of("vidA")) && replacing.getCount() > 0) {
                replacing.countDown();
                changed.await(10, TimeUnit.SECONDS);
            }
            finished.add(videos);
            return List.of();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = pool.submit(() -> syncPlaylist.syncIfConnected(eventId));
            assertThat(replacing.await(10, TimeUnit.SECONDS)).isTrue();
            // A 가 [A] 로 맞추는 중에 셋리가 [B] 로 바뀌고(편집 커밋을 흉내), 그 변경의 동기화 B 가 온다
            jdbc.update("DELETE FROM setlist_items WHERE event_id = ?", eventId);
            insertItem(b, 1);
            jdbc.update("UPDATE setlist_pages SET version = 2 WHERE event_id = ?", eventId);
            syncPlaylist.syncIfConnected(eventId);
            changed.countDown();
            first.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(finished).last().isEqualTo(List.of("vidB"));
    }

    @Test
    @DisplayName("리프레시 토큰이 폐기되면 자격 증명에 invalid_at 을 찍고 운영자에게 한 번 알린 뒤, 이후 자동 동기화는 유튜브를 부르지 않고 수동은 409")
    void revoked_token_stops_sync_and_alerts_once() throws Exception {
        credentials.save(new YouTubeCredential("rt"));
        when(playlistPort.create(anyString(), anyString())).thenThrow(new ApplicationException(ErrorCode.YOUTUBE_RECONNECT_REQUIRED));

        assertThatThrownBy(() -> syncPlaylist.syncIfConnected(eventId)).isInstanceOf(ApplicationException.class);
        assertThat(jdbc.queryForObject("SELECT invalid_at FROM youtube_credentials WHERE id = 1", java.sql.Timestamp.class)).isNotNull();
        ArgumentCaptor<String> alert = ArgumentCaptor.forClass(String.class);
        verify(alertPort, times(1)).send(alert.capture());
        assertThat(alert.getValue()).contains("[유튜브 연결 만료]", "/api/v1/admin/youtube/authorize");

        syncPlaylist.syncIfConnected(eventId);
        mvc.perform(post("/api/v1/admin/events/{id}/setlist/playlist", eventId).with(admin()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("YOUTUBE_RECONNECT_REQUIRED"));
        verify(playlistPort, times(1)).create(anyString(), anyString());
        verify(alertPort, times(1)).send(anyString());

        // 다시 연결하면(콜백이 토큰을 바꾸면) 표시가 지워진다
        tx.executeWithoutResult(status -> credentials.findById(YouTubeCredential.SINGLETON_ID).orElseThrow().replace("rt2"));
        assertThat(jdbc.queryForObject("SELECT invalid_at FROM youtube_credentials WHERE id = 1", java.sql.Timestamp.class)).isNull();
    }

    @Test
    @DisplayName("리프레시 토큰은 DB 에 암호문으로 저장되고 엔티티로는 원문을 읽는다 (test 프로파일은 YOUTUBE_TOKEN_KEY 설정)")
    void refresh_token_encrypted_at_rest() {
        credentials.save(new YouTubeCredential("1//0gSecretToken"));

        assertThat(jdbc.queryForObject("SELECT refresh_token FROM youtube_credentials WHERE id = 1", String.class))
                .startsWith("v1:").doesNotContain("SecretToken");
        assertThat(credentials.findById(YouTubeCredential.SINGLETON_ID).orElseThrow().getRefreshToken()).isEqualTo("1//0gSecretToken");
    }

    @Test
    @DisplayName("3곡 중 2번째 영상이 유튜브에 없어 건너뛰어도 동기화는 OK, 요약에 skipped=1, 운영자 알림 1회(행사·곡·영상·사유)")
    void skipped_video_alerts_operator() throws Exception {
        long lastRunId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM sync_runs", Long.class);
        credentials.save(new YouTubeCredential("rt"));
        when(playlistPort.create(anyString(), anyString())).thenReturn("PL1");
        when(playlistPort.replaceItems("PL1", List.of("vidA", "vidB", "vidC"))).thenReturn(List.of("vidB"));

        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a, b, c)))
                .andExpect(status().isOk());

        ArgumentCaptor<String> alert = ArgumentCaptor.forClass(String.class);
        verify(alertPort, timeout(10_000)).send(alert.capture());
        assertThat(alert.getValue()).contains("[재생목록 영상 없음] eventId=" + eventId, "건너뜀 1건", "videoNotFound",
                "songId=" + b + " \"악몽\" videoId=vidB").doesNotContain("vidA", "vidC");
        assertThat(jdbc.queryForList("SELECT CONCAT(status, ' ', summary) FROM sync_runs WHERE source = 'YOUTUBE' AND id > ?", String.class, lastRunId))
                .containsExactly("OK eventId=" + eventId + " playlistId=PL1 videos=2 skipped=1");
        verify(alertPort, times(1)).send(anyString());
        // 곡 마스터는 그대로다(대체 영상은 관리자가 고른다)
        assertThat(jdbc.queryForObject("SELECT youtube_video_id FROM songs WHERE id = ?", String.class, b)).isEqualTo("vidB");
    }

    @Test
    @DisplayName("모든 영상이 유튜브에 없으면 빈 재생목록으로 OK, 건너뛴 곡 전부를 알림 한 번에 묶는다")
    void all_videos_missing_alerts_once() throws Exception {
        credentials.save(new YouTubeCredential("rt"));
        when(playlistPort.create(anyString(), anyString())).thenReturn("PL1");
        when(playlistPort.replaceItems("PL1", List.of("vidA", "vidB"))).thenReturn(List.of("vidA", "vidB"));

        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON).content(editBody(a, b)))
                .andExpect(status().isOk());

        ArgumentCaptor<String> alert = ArgumentCaptor.forClass(String.class);
        verify(alertPort, timeout(10_000)).send(alert.capture());
        assertThat(alert.getValue()).contains("건너뜀 2건", "songId=" + a + " \"주저하는 연인들을 위해\" videoId=vidA", "songId=" + b + " \"악몽\" videoId=vidB");
        verify(alertPort, times(1)).send(anyString());
    }

    private void insertItem(Long songId, int position) {
        jdbc.update("INSERT INTO setlist_items (event_id, song_id, position, in_setlistfm, created_at, updated_at) "
                + "VALUES (?, ?, ?, FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", eventId, songId, position);
    }

    private void awaitSyncUnlocked() throws InterruptedException {
        for (int i = 0; i < 100 && Boolean.TRUE.equals(redis.hasKey("setlist:playlist-sync:" + eventId)); i++) {
            Thread.sleep(100);
        }
    }

    private void awaitPlaylistId(String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String id = jdbc.queryForObject("SELECT youtube_playlist_id FROM setlist_pages WHERE event_id = ?", String.class, eventId);
            if (expected.equals(id)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("재생목록 ID 가 저장되지 않았습니다: " + expected);
    }
}
