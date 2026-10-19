package com.stagelog.setlist.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.setlist.domain.SnapshotCodec.Entry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {

    @Test
    @DisplayName("항목 -> JSON -> 항목 왕복이 같고, JSON 키는 songId/position/inSetlistFm")
    void round_trip() {
        List<Entry> entries = List.of(new Entry(7L, 1, true), new Entry(3L, 2, false));

        String json = SnapshotCodec.encode(entries);

        assertThat(json).isEqualTo("[{\"songId\":7,\"position\":1,\"inSetlistFm\":true},{\"songId\":3,\"position\":2,\"inSetlistFm\":false}]");
        assertThat(SnapshotCodec.decode(json)).isEqualTo(entries);
        assertThat(SnapshotCodec.decode("[]")).isEmpty();
    }
}
