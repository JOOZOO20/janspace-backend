package com.stagelog.catalog.infrastructure;

import com.stagelog.catalog.application.port.TrackCandidate;
import com.stagelog.catalog.application.port.TrackLookupPort;
import com.stagelog.integration.itunes.ItunesSearchClient;
import com.stagelog.integration.itunes.ItunesTrack;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * iTunes 응답을 catalog 의 TrackCandidate 로 바꾼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItunesTrackLookupAdapter implements TrackLookupPort {

    private static final int LIMIT = 10;

    private final ItunesSearchClient client;

    @Override
    public List<TrackCandidate> search(String term, String country) {
        return client.search(term, country, LIMIT).stream().map(ItunesTrackLookupAdapter::toCandidate).toList();
    }

    static TrackCandidate toCandidate(ItunesTrack track) {
        return new TrackCandidate(track.trackId(), track.trackName(), track.collectionId(), track.collectionName(),
                track.artworkUrl100(), releaseDate(track.releaseDate()));
    }

    // "2021-03-11T12:00:00Z" -> 2021-03-11. 형식이 다르면 null (초안 정보라 실패시키지 않는다)
    private static LocalDate releaseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toLocalDate();
        } catch (DateTimeParseException e) {
            log.warn("[iTunes 발매일 파싱 실패] value={}", raw);
            return null;
        }
    }
}
