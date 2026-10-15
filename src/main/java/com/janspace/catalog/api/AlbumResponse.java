package com.janspace.catalog.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.janspace.catalog.application.AlbumSnapshot;
import com.janspace.common.locale.LocalizedText;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "앨범 응답. 곡 응답의 albumTitle·coverUrl 과 같은 값이다")
public record AlbumResponse(
        @Schema(description = "앨범 ID", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "앨범명. original 과 ko·en·zh-TW·ja 중 있는 언어",
                example = "{\"original\": \"전설\", \"ko\": \"전설\", \"en\": \"Legend\"}", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalizedText title,
        @Schema(description = "발매일", example = "2019-03-13", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate releaseDate,
        @Schema(description = "커버 공개 URL. 업로드 전이면 null", example = "https://cdn.janspace.app/albums/3.png",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String coverUrl,
        @Schema(description = "iTunes 앨범(collection) ID", example = "1456790868", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long itunesCollectionId) {

    static AlbumResponse from(AlbumSnapshot a) {
        return new AlbumResponse(a.id(), a.title(), a.releaseDate(), a.coverUrl(), a.itunesCollectionId());
    }
}
