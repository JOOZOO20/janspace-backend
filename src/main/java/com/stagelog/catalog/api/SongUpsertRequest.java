package com.stagelog.catalog.api;

import com.stagelog.catalog.application.UpsertSong;
import com.stagelog.common.locale.LocalizedText;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Optional;

/**
 * 관리자 곡 저장 요청 (병합). Optional 필드는 키가 없으면 null(유지), "키": null 이면 empty(지움)로 들어온다.
 * 이 구분은 Jackson 의 USE_NULL_FOR_MISSING_REFERENCE_VALUES 가 켜져 있어야 성립한다 (꺼져 있으면 둘 다 empty 로 읽힌다). 길이·개수 검증은 CatalogCommand 가 한다.
 */
@Schema(description = "곡 저장 요청 (병합). id 가 있으면 그 곡을, 없으면 한국어 제목이 같은 곡을 갱신하고 없으면 새로 만든다. "
        + "보낸 키만 반영하고 키가 없는 필드는 기존 값을 유지한다. aliases·itunesId·youtubeId·albumId 는 null 을 보내면 지운다")
public record SongUpsertRequest(
        @Schema(description = "곡 ID (갱신 시)", example = "12", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long id,
        @Schema(description = "제목. original 필수, ko·en·zh-TW·ja 선택. 보내면 원어와 번역 전체를 교체한다. 새 곡이면 필수, 갱신이면 생략 시 유지",
                example = "{\"original\": \"주저하는 연인들을 위해\", \"en\": \"For Lovers Who Hesitate\"}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalizedText title,
        @Schema(description = "original 의 로케일. 생략하면 유지 (새 곡은 ko)", example = "ko", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"ko", "en", "zh-TW", "ja"})
        String originalLocale,
        @Schema(description = "setlist.fm 표기 별칭 전체 (저장 시 정규화, 최대 50개, 각 255자). 보내면 전체 교체, null 이면 모두 지움, 생략하면 유지",
                example = "[\"For Lovers Who Hesitate\"]", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        Optional<List<String>> aliases,
        @Schema(description = "iTunes 곡 ID. null 이면 지움, 생략하면 유지", example = "1456790871",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        Optional<Long> itunesId,
        @Schema(description = "유튜브 영상 ID (최대 20자). null 이면 지움, 생략하면 유지", example = "dQw4w9WgXcQ",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        Optional<String> youtubeId,
        @Schema(description = "대표 앨범 ID. 앨범 제목·커버가 이 앨범에서 온다. null 이면 앨범 연결 해제, 생략하면 유지", example = "3",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        Optional<Long> albumId,
        @Schema(description = "새 선택(기록의 최고의 곡, 셋리 추가)에 쓸 수 있는지. 생략하거나 null 이면 유지 (새 곡은 true)", example = "true",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean selectable) {

    UpsertSong toCommand() {
        return new UpsertSong(id, title, originalLocale, aliases, itunesId, youtubeId, albumId, selectable);
    }
}
