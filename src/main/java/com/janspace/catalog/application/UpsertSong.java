package com.janspace.catalog.application;

import com.janspace.common.locale.LocalizedText;
import java.util.List;

/**
 * 곡 upsert 명령. id 가 있으면 그 곡을, 없으면 한국어 제목이 같은 곡을 갱신하고 그것도 없으면 만든다.
 * originalLocale 이 null 이면 ko.
 */
public record UpsertSong(
        Long id,
        LocalizedText title,
        String originalLocale,
        List<String> aliases,
        Long itunesTrackId,
        String youtubeVideoId,
        Long albumId) {
}
