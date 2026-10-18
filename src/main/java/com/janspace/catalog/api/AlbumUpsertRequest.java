package com.janspace.catalog.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.catalog.application.UpsertAlbum;
import com.janspace.common.locale.LocalizedText;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 관리자 앨범 저장 요청 (병합). Optional 필드는 키가 없으면 null(유지), "키": null 이면 empty(지움)로 들어온다.
 * 이 구분은 Jackson 의 USE_NULL_FOR_MISSING_REFERENCE_VALUES 가 켜져 있어야 성립한다 (꺼져 있으면 둘 다 empty 로 읽힌다).
 */
@Schema(description = "앨범 저장 요청 (병합). id 가 있으면 그 앨범을, 없으면 한국어 앨범명이 같은 앨범을 갱신하고 없으면 새로 만든다. "
        + "보낸 키만 반영하고 키가 없는 필드는 기존 값을 유지한다. 커버는 커버 업로드로만 바뀐다")
public record AlbumUpsertRequest(
        @Schema(description = "앨범 ID (갱신 시)", example = "3", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long id,
        @Schema(description = "앨범명. original 필수, ko·en·zh-TW·ja 선택. 보내면 원어와 번역 전체를 교체한다. 새 앨범이면 필수, 갱신이면 생략 시 유지",
                example = "{\"original\": \"전설\", \"en\": \"Legend\"}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        LocalizedText title,
        @Schema(description = "original 의 로케일. 생략하면 유지 (새 앨범은 ko)", example = "ko", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"ko", "en", "zh-TW", "ja"})
        String originalLocale,
        @Schema(description = "발매일. null 이면 지움, 생략하면 유지", example = "2019-03-13",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        Optional<LocalDate> releaseDate,
        @Schema(description = "iTunes 앨범(collection) ID. null 이면 지움, 생략하면 유지", example = "1456790868",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        Optional<Long> itunesCollectionId) {

    UpsertAlbum toCommand() {
        return new UpsertAlbum(id, title, originalLocale, releaseDate, itunesCollectionId);
    }
}
