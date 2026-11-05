package com.stagelog.catalog.application;

import com.stagelog.common.locale.LocalizedText;
import com.stagelog.media.application.ImageSet;
import java.time.LocalDate;

public record AlbumSnapshot(
        Long id,
        LocalizedText title,
        LocalDate releaseDate,
        String coverUrl,
        ImageSet coverImages,
        Long itunesCollectionId) {
}
