package com.janspace.catalog.application;

import com.janspace.common.locale.LocalizedText;
import java.time.LocalDate;

public record UpsertAlbum(
        Long id,
        LocalizedText title,
        String originalLocale,
        LocalDate releaseDate,
        Long itunesCollectionId) {
}
