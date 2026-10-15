package com.janspace.catalog.application;

import com.janspace.common.locale.LocalizedText;
import java.util.List;

/**
 * 다른 기능(setlist, attendance)과 API 에 공개하는 곡 스냅샷. title 에는 원어 로케일 키와 번역 키가 모두 들어간다.
 * albumTitle·coverUrl 은 대표 앨범에서 오며 앨범이 없으면 null. selectable 이 false 면 새 선택에 쓰지 않는다.
 */
public record SongSnapshot(
        Long id,
        LocalizedText title,
        List<String> aliases,
        Long itunesTrackId,
        String youtubeVideoId,
        Long albumId,
        LocalizedText albumTitle,
        String coverUrl,
        boolean selectable) {
}
