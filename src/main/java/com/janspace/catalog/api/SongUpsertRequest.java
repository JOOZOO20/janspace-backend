package com.janspace.catalog.api;

import com.janspace.catalog.application.UpsertSong;
import com.janspace.common.locale.LocalizedText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "곡 upsert 요청. id 가 있으면 그 곡을, 없으면 한국어 제목이 같은 곡을 갱신하고 없으면 새로 만든다")
public record SongUpsertRequest(
        @Schema(description = "곡 ID (갱신 시)", example = "12", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long id,
        @Schema(description = "제목. original 필수, ko·en·zh-TW·ja 선택", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalizedText title,
        @Schema(description = "original 의 로케일. 생략하면 ko", example = "ko", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"ko", "en", "zh-TW", "ja"})
        String originalLocale,
        @Schema(description = "setlist.fm 표기 별칭 전체 (저장 시 정규화)", example = "[\"For Lovers Who Hesitate\"]",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 50) List<String> aliases,
        @Schema(description = "iTunes 곡 ID", example = "1456790871", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long itunesId,
        @Schema(description = "유튜브 영상 ID", example = "dQw4w9WgXcQ", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 20) String youtubeId,
        @Schema(description = "대표 앨범 ID", example = "3", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long albumId) {

    UpsertSong toCommand() {
        return new UpsertSong(id, title, originalLocale, aliases, itunesId, youtubeId, albumId);
    }
}
