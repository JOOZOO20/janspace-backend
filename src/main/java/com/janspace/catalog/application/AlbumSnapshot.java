package com.janspace.catalog.application;

import com.janspace.common.locale.LocalizedText;
import java.time.LocalDate;

public record AlbumSnapshot(
        Long id,
        LocalizedText title,
        LocalDate releaseDate,
        String coverUrl,
        Long itunesCollectionId) {
}
