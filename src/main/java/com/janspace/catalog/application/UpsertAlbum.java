package com.janspace.catalog.application;

import com.janspace.common.locale.LocalizedText;
import java.time.LocalDate;
import java.util.Optional;

/**
 * 앨범 저장 명령 (병합). 곡 저장과 같은 규칙: Optional 필드는 null = 유지, empty = 지움. title·originalLocale 은 null = 유지.
 * 새 앨범은 title 이 필수다. 커버는 별도 업로드로만 바뀐다.
 */
public record UpsertAlbum(
        Long id,
        LocalizedText title,
        String originalLocale,
        Optional<LocalDate> releaseDate,
        Optional<Long> itunesCollectionId) {
}
