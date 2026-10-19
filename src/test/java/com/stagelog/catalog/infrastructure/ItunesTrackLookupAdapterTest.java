package com.stagelog.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.catalog.application.port.TrackCandidate;
import com.stagelog.integration.itunes.ItunesTrack;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItunesTrackLookupAdapterTest {

    @Test
    @DisplayName("iTunes 곡을 후보로 바꾼다. 발매일은 날짜만 남기고 없거나 형식이 다르면 null")
    void maps_track_to_candidate() {
        TrackCandidate c = ItunesTrackLookupAdapter.toCandidate(new ItunesTrack(
                1900000101L, "떠도는 별들을 위해", 1900000100L, "등대", "https://x/100x100bb.jpg", "2021-03-11T12:00:00Z"));
        assertThat(c.trackId()).isEqualTo(1900000101L);
        assertThat(c.trackName()).isEqualTo("떠도는 별들을 위해");
        assertThat(c.collectionId()).isEqualTo(1900000100L);
        assertThat(c.collectionName()).isEqualTo("등대");
        assertThat(c.artworkUrl()).isEqualTo("https://x/100x100bb.jpg");
        assertThat(c.releaseDate()).isEqualTo(LocalDate.of(2021, 3, 11));

        assertThat(ItunesTrackLookupAdapter.toCandidate(new ItunesTrack(1L, "t", null, null, null, null)).releaseDate()).isNull();
        assertThat(ItunesTrackLookupAdapter.toCandidate(new ItunesTrack(1L, "t", null, null, null, "2019-03")).releaseDate()).isNull();
    }
}
