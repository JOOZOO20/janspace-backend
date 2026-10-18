package com.janspace.catalog.api;

import com.janspace.catalog.application.SongSnapshot;
import com.janspace.common.locale.LocalizedText;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "곡 응답. 프론트 Song 타입에 albumId·coverUrl 을 더한 형태")
public record SongResponse(
        @Schema(description = "곡 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "제목. original 과 ko·en·zh-TW·ja 중 있는 언어", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalizedText title,
        @Schema(description = "setlist.fm 표기 별칭 (정규화된 소문자)", example = "[\"for lovers who hesitate\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> aliases,
        @Schema(description = "iTunes 곡 ID", example = "1456790871", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long itunesId,
        @Schema(description = "유튜브 영상 ID", example = "dQw4w9WgXcQ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String youtubeId,
        @Schema(description = "대표 앨범 ID", example = "3", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long albumId,
        @Schema(description = "대표 앨범 커버 공개 URL", example = "https://cdn.janspace.app/albums/3.png",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String coverUrl) {

    static SongResponse from(SongSnapshot s) {
        return new SongResponse(s.id(), s.title(), s.aliases(), s.itunesTrackId(), s.youtubeVideoId(), s.albumId(), s.coverUrl());
    }
}
