package com.janspace.setlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.setlist.domain.YouTubeCredential;
import com.janspace.setlist.domain.YouTubeCredentialRepository;
import com.janspace.setlist.support.SetlistIntegrationTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 재생목록: 채널 미연결이면 건너뜀(수동은 409), 연결 뒤 첫 편집에 생성 + 항목, 다음 편집에 같은 재생목록 항목만 갱신. 리스너는 커밋 뒤 비동기.
 */
class PlaylistSyncIntegrationTest extends SetlistIntegrationTest {

    @Autowired
    YouTubeCredentialRepository credentials;

    Long eventId;
    Long a;
    Long b;
    Long noVideo;

    @BeforeEach
    void setUp() {
        eventId = newEvent("잔나비 단독 공연", today.plusDays(1));
        a = newSong("주저하는 연인들을 위해", null, "vidA");
        b = newSong("악몽", null, "vidB");
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
        credentials.save(new YouTubeCredential("rt", null));
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
