package com.stagelog.catalog.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.catalog.application.AlbumSnapshot;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.media.application.ImageSet;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "앨범 응답. 곡 응답의 albumTitle·coverUrl 과 같은 값이다")
public record AlbumResponse(
        @Schema(description = "앨범 ID", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
        Long id,
        @Schema(description = "앨범명. original 과 ko·en·zh-TW·ja 중 있는 언어",
                example = "{\"original\": \"등대\", \"ko\": \"등대\", \"en\": \"Lighthouse\"}", requiredMode = Schema.RequiredMode.REQUIRED)
        LocalizedText title,
        @Schema(description = "발매일", example = "2021-03-11", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate releaseDate,
        @Schema(description = "커버 공개 URL. 업로드 전이면 null", example = "https://cdn.stagelog.app/albums/3.png",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String coverUrl,
        @Schema(description = "커버의 화면별 이미지 URL. 변환이 끝나지 않은 자리는 coverUrl 과 같다. 업로드 전이면 null",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        ImageSet images,
        @Schema(description = "iTunes 앨범(collection) ID", example = "1900000100", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long itunesCollectionId) {

    static AlbumResponse from(AlbumSnapshot a) {
        return new AlbumResponse(a.id(), a.title(), a.releaseDate(), a.coverUrl(), a.coverImages(), a.itunesCollectionId());
    }
}
