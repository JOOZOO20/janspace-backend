package com.stagelog.setlist.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.setlist.application.RevisionView;
import com.stagelog.setlist.application.SetlistEntry;
import com.stagelog.setlist.application.SetlistView;
import com.stagelog.setlist.application.UnmatchedUseCase;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * 셋리 API 응답 DTO 모음 (프론트 Setlist·SetlistRevision·UnmatchedSong 계약). 편집자 정보는 어떤 DTO 에도 없다.
 */
public final class SetlistResponses {

    private SetlistResponses() {
    }

    @Schema(description = "셋리 항목")
    public record EntryResponse(
            @Schema(description = "곡 ID", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
            Long songId,
            @Schema(description = "순서 (1부터)", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            int order,
            @Schema(description = "setlist.fm 에 없는 곡 여부 (위키에만 있음)", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean notOnSetlistFm) {

        static EntryResponse from(SetlistEntry e) {
            return new EntryResponse(e.songId(), e.position(), !e.inSetlistFm());
        }

        static List<EntryResponse> from(List<SetlistEntry> entries) {
            return entries.stream().map(EntryResponse::from).toList();
        }
    }

    @Schema(description = "셋리 위키 페이지 (프론트 Setlist 계약)")
    public record SetlistResponse(
            @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
            Long eventId,
            @Schema(description = "현재 버전. 편집 요청의 version 으로 보내면 충돌을 감지한다", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
            int version,
            @Schema(description = "관리자 순서 확정 여부 (병합 여부와 독립)", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean orderConfirmed,
            @Schema(description = "항목 (순서대로)", example = "[{\"songId\":7,\"order\":1,\"notOnSetlistFm\":false}]",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<EntryResponse> entries,
            @Schema(description = "마지막 변경 시각 (UTC). 페이지가 없으면 null", example = "2026-10-23T14:10:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant updatedAt) {

        static SetlistResponse from(SetlistView view) {
            return new SetlistResponse(view.eventId(), view.version(), view.orderConfirmed(), EntryResponse.from(view.entries()), view.updatedAt());
        }
    }

    @Schema(description = "셋리 리비전 (프론트 SetlistRevision 계약, 익명)")
    public record RevisionResponse(
            @Schema(description = "버전", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
            int version,
            @Schema(description = "변경 시각 (UTC)", example = "2026-10-23T14:10:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
            Instant changedAt,
            @Schema(description = "출처", example = "FAN", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"FAN", "SETLISTFM_SEED", "SETLISTFM_MERGE", "REVERT"})
            String source,
            @Schema(description = "그 시점의 항목", example = "[{\"songId\":7,\"order\":1,\"notOnSetlistFm\":false}]",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<EntryResponse> entries) {

        static RevisionResponse from(RevisionView view) {
            return new RevisionResponse(view.version(), view.changedAt(), view.source().name(), EntryResponse.from(view.entries()));
        }
    }

    @Schema(description = "미매칭 setlist.fm 곡명 (프론트 UnmatchedSong 계약)")
    public record UnmatchedSongResponse(
            @Schema(description = "미매칭 ID", example = "5", requiredMode = Schema.RequiredMode.REQUIRED)
            Long id,
            @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
            Long eventId,
            @Schema(description = "setlist.fm 표기 곡명", example = "for stars who wander", requiredMode = Schema.RequiredMode.REQUIRED)
            String setlistFmName) {

        static UnmatchedSongResponse from(UnmatchedUseCase.Unmatched u) {
            return new UnmatchedSongResponse(u.id(), u.eventId(), u.setlistFmName());
        }
    }

    @Schema(description = "수동 병합 결과")
    public record MergeResponse(
            @Schema(description = "셋리에 변화가 있었는지", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean changed) {
    }

    @Schema(description = "재생목록 동기화 결과")
    public record PlaylistResponse(
            @Schema(description = "유튜브 재생목록 ID", example = "PLx", requiredMode = Schema.RequiredMode.REQUIRED)
            String playlistId,
            @Schema(description = "재생목록 공개 URL", example = "https://www.youtube.com/playlist?list=PLx", requiredMode = Schema.RequiredMode.REQUIRED)
            String playlistUrl) {
    }

    @Schema(description = "유튜브 OAuth 인가 URL")
    public record AuthorizeUrlResponse(
            @Schema(description = "관리자가 브라우저로 열 구글 동의 화면 URL", example = "https://accounts.google.com/o/oauth2/v2/auth?...",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String authorizeUrl) {
    }
}
