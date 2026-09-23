package com.janspace.catalog.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.catalog.application.UpsertAlbum;
import com.janspace.common.locale.LocalizedText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

@Schema(description = "앨범 upsert 요청. id 가 있으면 그 앨범을, 없으면 한국어 앨범명이 같은 앨범을 갱신하고 없으면 새로 만든다")
public record AlbumUpsertRequest(
        @Schema(description = "앨범 ID (갱신 시)", example = "3", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long id,
        @Schema(description = "앨범명. original 필수, ko·en·zh-TW·ja 선택", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull LocalizedText title,
        @Schema(description = "original 의 로케일. 생략하면 ko", example = "ko", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"ko", "en", "zh-TW", "ja"})
        String originalLocale,
        @Schema(description = "발매일", example = "2019-03-13", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate releaseDate,
        @Schema(description = "iTunes 앨범(collection) ID", example = "1456790868", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long itunesCollectionId) {

    UpsertAlbum toCommand() {
        return new UpsertAlbum(id, title, originalLocale, releaseDate, itunesCollectionId);
    }
}
