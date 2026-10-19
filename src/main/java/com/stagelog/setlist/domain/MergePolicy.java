package com.stagelog.setlist.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * setlist.fm 병합 정책 (순수 함수, 설계). 곡 집합은 합집합, 순서는 setlist.fm 이 먼저, 위키에만 있는 곡은 상대 순서를 지키며 뒤에.
 * 결정적이라 같은 입력으로 다시 실행하면 changed = false.
 */
public final class MergePolicy {

    private MergePolicy() {
    }

    public record Entry(Long songId, boolean inSetlistFm) {
    }

    public record Result(List<Entry> entries, boolean changed) {
    }

    // fm 이 비어 있으면(setlist.fm 미등록) 위키를 그대로 둔다
    public static Result merge(List<Entry> wiki, List<Long> fmSongIds) {
        if (fmSongIds.isEmpty()) {
            return new Result(List.copyOf(wiki), false);
        }
        // 1. setlist.fm 순서 (중복 제거)
        Set<Long> fm = new LinkedHashSet<>(fmSongIds);
        List<Entry> merged = new ArrayList<>();
        fm.forEach(songId -> merged.add(new Entry(songId, true)));
        // 2. 위키에만 있는 곡을 상대 순서 그대로 뒤에 붙인다
        wiki.stream().filter(e -> !fm.contains(e.songId())).forEach(e -> merged.add(new Entry(e.songId(), false)));
        return new Result(List.copyOf(merged), !merged.equals(wiki));
    }
}
