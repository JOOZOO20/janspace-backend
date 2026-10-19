package com.stagelog.catalog.application;

import com.stagelog.common.locale.LocalizedText;
import java.time.LocalDate;

public record AlbumSnapshot(
        Long id,
        LocalizedText title,
        LocalDate releaseDate,
        String coverUrl,
        Long itunesCollectionId) {
}
