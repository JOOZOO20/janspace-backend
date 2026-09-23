package com.janspace.catalog.application.port;

import java.time.LocalDate;

public record TrackCandidate(
        Long trackId,
        String trackName,
        Long collectionId,
        String collectionName,
        String artworkUrl,
        LocalDate releaseDate) {
}
