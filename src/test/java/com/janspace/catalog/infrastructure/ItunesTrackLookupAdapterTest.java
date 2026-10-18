package com.janspace.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.catalog.application.port.TrackCandidate;
import com.janspace.integration.itunes.ItunesTrack;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItunesTrackLookupAdapterTest {

    @Test
    @DisplayName("iTunes 곡을 후보로 바꾼다. 발매일은 날짜만 남기고 없거나 형식이 다르면 null")
    void maps_track_to_candidate() {
        TrackCandidate c = ItunesTrackLookupAdapter.toCandidate(new ItunesTrack(
                1456790871L, "주저하는 연인들을 위해", 1456790868L, "전설", "https://x/100x100bb.jpg", "2019-03-13T12:00:00Z"));
        assertThat(c.trackId()).isEqualTo(1456790871L);
        assertThat(c.trackName()).isEqualTo("주저하는 연인들을 위해");
        assertThat(c.collectionId()).isEqualTo(1456790868L);
        assertThat(c.collectionName()).isEqualTo("전설");
        assertThat(c.artworkUrl()).isEqualTo("https://x/100x100bb.jpg");
        assertThat(c.releaseDate()).isEqualTo(LocalDate.of(2019, 3, 13));

        assertThat(ItunesTrackLookupAdapter.toCandidate(new ItunesTrack(1L, "t", null, null, null, null)).releaseDate()).isNull();
        assertThat(ItunesTrackLookupAdapter.toCandidate(new ItunesTrack(1L, "t", null, null, null, "2019-03")).releaseDate()).isNull();
    }
}
