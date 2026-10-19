package com.stagelog.catalog.api;

import com.stagelog.catalog.application.SongSnapshot;
import com.stagelog.common.locale.LocalizedText;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "곡 응답. 공개 목록·검색과 관리자 저장이 같은 형태를 쓴다. 앨범 제목·커버는 대표 앨범에서 온다")
public record SongResponse(
        @Schema(description = "곡 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "제목. original 과 ko·en·zh-TW·ja 중 있는 언어",
                example = "{\"original\": \"떠도는 별들을 위해\", \"ko\": \"떠도는 별들을 위해\", \"en\": \"For Stars Who Wander\"}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocalizedText title,
        @Schema(description = "setlist.fm 표기 별칭 (정규화된 소문자)", example = "[\"for stars who wander\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> aliases,
        @Schema(description = "iTunes 곡 ID", example = "1900000101", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long itunesId,
        @Schema(description = "유튜브 영상 ID", example = "dQw4w9WgXcQ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String youtubeId,
        @Schema(description = "대표 앨범 ID. 앨범이 없으면 null", example = "3", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long albumId,
        @Schema(description = "대표 앨범 제목. original 과 ko·en·zh-TW·ja 중 있는 언어. 앨범이 없으면 null",
                example = "{\"original\": \"등대\", \"ko\": \"등대\", \"en\": \"Lighthouse\"}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalizedText albumTitle,
        @Schema(description = "대표 앨범 커버 공개 URL. 앨범이 없거나 커버 업로드 전이면 null", example = "https://cdn.stagelog.app/albums/3.png",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String coverUrl,
        @Schema(description = "새 선택(기록의 최고의 곡, 셋리 추가, 미매칭 연결)에 쓸 수 있는지. false 면 기존 기록·셋리 표시용으로만 남은 곡",
                example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean selectable) {

    static SongResponse from(SongSnapshot s) {
        return new SongResponse(s.id(), s.title(), s.aliases(), s.itunesTrackId(), s.youtubeVideoId(), s.albumId(), s.albumTitle(),
                s.coverUrl(), s.selectable());
    }
}
