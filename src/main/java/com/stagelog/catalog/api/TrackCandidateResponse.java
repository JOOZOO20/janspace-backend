package com.stagelog.catalog.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.catalog.application.port.TrackCandidate;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

@Schema(description = "iTunes 검색 후보. 곡·앨범 ID 와 아트워크 초안")
public record TrackCandidateResponse(
        @Schema(description = "iTunes 곡 ID", example = "1900000101", requiredMode = Schema.RequiredMode.REQUIRED)
        Long trackId,
        @Schema(description = "곡 제목 (country 에 따른 언어)", example = "떠도는 별들을 위해", requiredMode = Schema.RequiredMode.REQUIRED)
        String trackName,
        @Schema(description = "iTunes 앨범(collection) ID", example = "1900000100", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long collectionId,
        @Schema(description = "앨범명", example = "등대", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String collectionName,
        @Schema(description = "아트워크 URL (100px)", example = "https://is1-ssl.mzstatic.com/image/thumb/.../100x100bb.jpg",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String artworkUrl,
        @Schema(description = "발매일", example = "2021-03-11", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate releaseDate) {

    static TrackCandidateResponse from(TrackCandidate c) {
        return new TrackCandidateResponse(c.trackId(), c.trackName(), c.collectionId(), c.collectionName(), c.artworkUrl(),
                c.releaseDate());
    }
}
