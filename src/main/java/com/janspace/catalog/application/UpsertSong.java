package com.janspace.catalog.application;

import com.janspace.common.locale.LocalizedText;
import java.util.List;
import java.util.Optional;

/**
 * 곡 저장 명령 (병합). id 가 있으면 그 곡을, 없으면 한국어 제목이 같은 곡을 갱신하고 그것도 없으면 만든다.
 * 보낸 값만 반영한다: Optional 필드는 null = 유지, empty = 지움. title·originalLocale·selectable 은 null = 유지.
 * 새 곡은 title 이 필수이고 originalLocale 은 ko, selectable 은 true 로 시작한다.
 */
public record UpsertSong(
        Long id,
        LocalizedText title,
        String originalLocale,
        Optional<List<String>> aliases,
        Optional<Long> itunesTrackId,
        Optional<String> youtubeVideoId,
        Optional<Long> albumId,
        Boolean selectable) {

    // 나열한 필드를 모두 보낸 것으로 본다 (null = 지움). selectable 은 유지
    public UpsertSong(Long id, LocalizedText title, String originalLocale, List<String> aliases, Long itunesTrackId,
                      String youtubeVideoId, Long albumId) {
        this(id, title, originalLocale, Optional.ofNullable(aliases), Optional.ofNullable(itunesTrackId),
                Optional.ofNullable(youtubeVideoId), Optional.ofNullable(albumId), null);
    }
}
