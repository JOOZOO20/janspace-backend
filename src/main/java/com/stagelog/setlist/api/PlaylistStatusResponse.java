package com.stagelog.setlist.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.setlist.application.PlaylistSyncStatusQuery.LastRun;
import com.stagelog.setlist.application.PlaylistSyncStatusQuery.MissingVideo;
import com.stagelog.setlist.application.PlaylistSyncStatusQuery.PlaylistStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 진단용 재생목록 동기화 상태. YouTube 를 조회하지 않은 DB 기록 기준이다.
 */
@Schema(description = "행사 재생목록 동기화 상태 (DB 기록 기준, YouTube 실시간 조회 없음)")
public record PlaylistStatusResponse(
        @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long eventId,
        @Schema(description = "유튜브 재생목록 ID. 아직 없으면 null", example = "PLabc123", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String playlistId,
        @Schema(description = "Quota 부족으로 미룬 시각 (UTC). 미룬 동기화가 없으면 null", example = "2026-11-05T17:30:00Z",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant deferredAt,
        @Schema(description = "유튜브 채널 연결 상태", requiredMode = Schema.RequiredMode.REQUIRED)
        CredentialResponse credential,
        @Schema(description = "이 행사의 마지막 재생목록 동기화 실행. 없으면 null", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LastRunResponse lastRun,
        @Schema(description = "현재 셋리 version", example = "4", requiredMode = Schema.RequiredMode.REQUIRED)
        int setlistVersion,
        @Schema(description = "최신 셋리 리비전 ID. 없으면 null", example = "88", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long latestRevisionId,
        @Schema(description = "최신 리비전 시각 (UTC)", example = "2026-11-05T03:00:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant latestRevisionAt,
        @Schema(description = "마지막 실행 종료 뒤 셋리가 바뀌었는지", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean setlistChangedAfterLastRun,
        @Schema(description = "현재 셋리가 요구하는 영상 수 (곡 마스터에 영상 ID 가 있는 항목)", example = "18", requiredMode = Schema.RequiredMode.REQUIRED)
        int targetVideoCount,
        @Schema(description = "목표 영상 수 - 마지막 실행이 반영한 수. 마지막 실행 요약이 없으면 null", example = "0",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer videoCountGap,
        @Schema(description = "영상 ID 가 없어 동기화가 건너뛰는 셋리 항목", requiredMode = Schema.RequiredMode.REQUIRED)
        List<MissingVideoResponse> songsWithoutVideo,
        @Schema(description = "오늘(태평양 날짜) 이 행사가 쓴 Quota", requiredMode = Schema.RequiredMode.REQUIRED)
        QuotaTodayResponse quotaToday) {

    @Schema(description = "유튜브 채널 연결 상태")
    public record CredentialResponse(
            @Schema(description = "채널이 연결된 적이 있는지", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean connected,
            @Schema(description = "리프레시 토큰이 폐기·만료(invalid_grant)된 시각 (UTC). 정상이면 null", example = "2026-11-04T09:00:00Z",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant invalidAt) {
    }

    @Schema(description = "마지막 재생목록 동기화 실행")
    public record LastRunResponse(
            @Schema(description = "실행 ID (sync_runs.id)", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
            Long runId,
            @Schema(description = "실행 상태", example = "PARTIAL", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"OK", "FAILED", "PARTIAL", "DEFERRED"})
            String status,
            @Schema(description = "시작 시각 (UTC)", example = "2026-11-05T03:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant startedAt,
            @Schema(description = "종료 시각 (UTC)", example = "2026-11-05T03:00:09Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant finishedAt,
            @Schema(description = "반영한 영상 수 (요약 기준). 요약에 없으면 null", example = "17", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer syncedVideos,
            @Schema(description = "유튜브에 없어 건너뛴 영상 수 (videoNotFound). 요약에 없으면 null", example = "1",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer skippedVideos,
            @Schema(description = "실행 요약 또는 실패 사유", example = "eventId=12 playlistId=PLabc videos=17 skipped=1",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String message) {
    }

    @Schema(description = "영상 ID 가 없는 셋리 항목")
    public record MissingVideoResponse(
            @Schema(description = "곡 ID", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
            Long songId,
            @Schema(description = "셋리 순서", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
            int position,
            @Schema(description = "곡 원어 제목. 곡이 없으면 null", example = "떠도는 별들을 위해", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String title) {
    }

    @Schema(description = "오늘 이 행사의 Quota 사용")
    public record QuotaTodayResponse(
            @Schema(description = "Quota 날짜 (태평양 시간 기준)", example = "2026-11-05", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate date,
            @Schema(description = "이 행사가 쓴 unit (CONSUMED + UNKNOWN)", example = "152", requiredMode = Schema.RequiredMode.REQUIRED)
            long eventUnits) {
    }

    static PlaylistStatusResponse from(PlaylistStatus s) {
        LastRun last = s.lastRun();
        return new PlaylistStatusResponse(s.eventId(), s.playlistId(), s.deferredAt(),
                new CredentialResponse(s.connected(), s.credentialInvalidAt()),
                last == null ? null : new LastRunResponse(last.run().id(), last.run().status(), last.run().startedAt(),
                        last.run().finishedAt(), last.syncedVideos(), last.skippedVideos(), last.run().summary()),
                s.setlistVersion(), s.latestRevisionId(), s.latestRevisionAt(), s.setlistChangedAfterLastRun(), s.targetVideoCount(),
                s.videoCountGap(), s.songsWithoutVideo().stream().map(PlaylistStatusResponse::missing).toList(),
                new QuotaTodayResponse(s.quotaDate(), s.quotaUnitsToday()));
    }

    private static MissingVideoResponse missing(MissingVideo m) {
        return new MissingVideoResponse(m.songId(), m.position(), m.title());
    }
}
