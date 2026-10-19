package com.stagelog.integration.itunes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * iTunes Search 응답의 곡 한 건. 필요한 필드만 매핑한다. releaseDate 는 "2019-03-13T12:00:00Z" 형식의 문자열.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItunesTrack(
        Long trackId,
        String trackName,
        Long collectionId,
        String collectionName,
        String artworkUrl100,
        String releaseDate) {
}
