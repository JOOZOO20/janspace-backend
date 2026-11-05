package com.stagelog.setlist.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.ops.application.SyncRunRecorder;
import com.stagelog.setlist.application.YouTubeQuota;
import com.stagelog.setlist.domain.YouTubeCredential;
import com.stagelog.setlist.domain.YouTubeCredentialRepository;
import com.stagelog.setlist.support.SetlistIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 재생목록 동기화 상태 진단: 마지막 YOUTUBE 실행(요약의 반영·건너뜀 수), 목표 영상 수와의 차이, 영상 ID 없는 곡,
 * 실행 뒤 셋리 변경, 채널 invalid_at, 오늘 행사 Quota. YouTube 는 부르지 않는다 (playlistPort 목 호출 없음).
 */
class AdminPlaylistStatusIntegrationTest extends SetlistIntegrationTest {

    @Autowired
    SyncRunRecorder syncRuns;
    @Autowired
    YouTubeCredentialRepository credentials;
    @Autowired
    YouTubeQuota quota;

    Long eventId;
    Long withVideo;
    Long noVideo;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE sync_runs");
        jdbc.execute("TRUNCATE TABLE api_quota_ledger");
        eventId = newEvent("스타더스트 클럽 단독 공연", today.plusDays(1));
        withVideo = newSong("떠도는 별들을 위해", null, "vidA");
        noVideo = newSong("영상 없는 곡", null, null);
    }

    @Test
    @DisplayName("마지막 실행·차이·영상 없는 곡·실행 뒤 셋리 변경·채널 만료·오늘 Quota 를 DB 기록으로 돌려준다")
    void status_from_recorded_state() throws Exception {
        // 채널 미연결이라 편집이 동기화를 부르지 않는다
        edit(withVideo, noVideo);
        Long otherRun = syncRuns.start("YOUTUBE");
        syncRuns.succeed(otherRun, "eventId=" + (eventId + 100) + " playlistId=PL9 videos=9 skipped=0");
        Long runId = syncRuns.start("YOUTUBE");
        syncRuns.partial(runId, "items mismatch eventId=" + eventId + " playlistId=PL1 videos=0 skipped=1");
        jdbc.update("INSERT INTO api_quota_ledger (provider, quota_date, bucket, operation, units, status, event_id, sync_run_id, created_at, updated_at) "
                + "VALUES ('YOUTUBE', ?, 'AUTO', 'INSERT', 50, 'CONSUMED', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)), "
                + "('YOUTUBE', ?, 'AUTO', 'LIST', 1, 'RELEASED', ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                quota.today(), eventId, runId, quota.today(), eventId, runId);

        mvc.perform(get("/api/v1/admin/events/{id}/setlist/playlist-status", eventId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastRun.runId").value(runId))
                .andExpect(jsonPath("$.data.lastRun.status").value("PARTIAL"))
                .andExpect(jsonPath("$.data.lastRun.syncedVideos").value(0))
                .andExpect(jsonPath("$.data.lastRun.skippedVideos").value(1))
                .andExpect(jsonPath("$.data.targetVideoCount").value(1))
                .andExpect(jsonPath("$.data.videoCountGap").value(1))
                .andExpect(jsonPath("$.data.songsWithoutVideo.length()").value(1))
                .andExpect(jsonPath("$.data.songsWithoutVideo[0].songId").value(noVideo))
                .andExpect(jsonPath("$.data.songsWithoutVideo[0].position").value(2))
                .andExpect(jsonPath("$.data.setlistChangedAfterLastRun").value(false))
                .andExpect(jsonPath("$.data.latestRevisionId").isNumber())
                .andExpect(jsonPath("$.data.credential.connected").value(false))
                .andExpect(jsonPath("$.data.quotaToday.date").value(quota.today().toString()))
                .andExpect(jsonPath("$.data.quotaToday.eventUnits").value(50));

        // 실행 뒤 셋리 변경, 채널 만료
        edit(withVideo);
        credentials.save(new YouTubeCredential("rt"));
        jdbc.update("UPDATE youtube_credentials SET invalid_at = UTC_TIMESTAMP(6)");

        mvc.perform(get("/api/v1/admin/events/{id}/setlist/playlist-status", eventId).with(admin()))
                .andExpect(jsonPath("$.data.setlistChangedAfterLastRun").value(true))
                .andExpect(jsonPath("$.data.songsWithoutVideo.length()").value(0))
                .andExpect(jsonPath("$.data.credential.connected").value(true))
                .andExpect(jsonPath("$.data.credential.invalidAt").isString());
    }

    @Test
    @DisplayName("실행 이력이 없으면 lastRun·videoCountGap 은 null, 없는 행사는 404")
    void no_run_and_missing_event() throws Exception {
        mvc.perform(get("/api/v1/admin/events/{id}/setlist/playlist-status", eventId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastRun").doesNotExist())
                .andExpect(jsonPath("$.data.videoCountGap").doesNotExist())
                .andExpect(jsonPath("$.data.playlistId").doesNotExist())
                .andExpect(jsonPath("$.data.quotaToday.eventUnits").value(0));
        mvc.perform(get("/api/v1/admin/events/{id}/setlist/playlist-status", 999_999L).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    private void edit(Long... songIds) throws Exception {
        mvc.perform(put("/api/v1/events/{id}/setlist", eventId).with(user(USER)).contentType(MediaType.APPLICATION_JSON)
                .content(editBody(songIds))).andExpect(status().isOk());
    }
}
