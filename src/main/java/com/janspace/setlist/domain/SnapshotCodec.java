package com.janspace.setlist.domain;

import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 리비전 snapshot JSON <-> 항목 목록. 형식: [{"songId":1,"position":1,"inSetlistFm":true}].
 * 되돌리기가 "setlist.fm 에 없음" 표시까지 복원해야 하므로 플래그도 함께 담는다.
 */
public final class SnapshotCodec {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<Entry>> ENTRIES = new TypeReference<>() {
    };

    private SnapshotCodec() {
    }

    public record Entry(Long songId, int position, boolean inSetlistFm) {
    }

    public static String encode(List<Entry> entries) {
        return MAPPER.writeValueAsString(entries);
    }

    public static List<Entry> decode(String json) {
        return MAPPER.readValue(json, ENTRIES);
    }
}
