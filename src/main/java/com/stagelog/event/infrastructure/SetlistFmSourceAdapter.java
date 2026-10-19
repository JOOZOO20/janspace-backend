package com.stagelog.event.infrastructure;

import com.stagelog.event.application.port.SetlistFmEventPort;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourcePolicy.SourceVenue;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.integration.setlistfm.SetlistFmClient;
import com.stagelog.integration.setlistfm.SetlistFmSetlist;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * setlist.fm 셋리 -> SourceSnapshot. 공연장 이름·도시·국가만 주고 좌표는 coords 가 있을 때만(도시 좌표라 정밀하지 않다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetlistFmSourceAdapter implements SetlistFmEventPort {

    static final DateTimeFormatter EVENT_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final SetlistFmClient client;

    @Override
    public boolean isEnabled() {
        return client.isEnabled();
    }

    @Override
    public List<SourceSnapshot> page(int page) {
        List<SourceSnapshot> out = new ArrayList<>();
        for (SetlistFmSetlist s : client.listArtistSetlists(client.artistMbid(), page).items()) {
            LocalDate date = parse(s.eventDate());
            if (s.id() == null || date == null) {
                log.warn("[setlist.fm 항목 무시] id 또는 eventDate 없음. id={}", s.id());
                continue;
            }
            out.add(new SourceSnapshot(s.id(), date.toString(), null, null, null, null, venue(s.venue()), null, date, null));
        }
        return out;
    }

    private static SourceVenue venue(SetlistFmSetlist.Venue v) {
        if (v == null || v.name() == null || v.name().isBlank()) {
            return null;
        }
        SetlistFmSetlist.City city = v.city();
        GeoPoint point = null;
        if (city != null && city.coords() != null && city.coords().lat() != null && city.coords().lng() != null) {
            try {
                point = new GeoPoint(city.coords().lat(), city.coords().lng());
            } catch (IllegalArgumentException ignored) {
                // 범위 밖 좌표는 없음으로
            }
        }
        return new SourceVenue(v.id(), v.name(), city == null ? null : city.name(),
                city == null || city.country() == null ? null : city.country().code(), point, null);
    }

    private static LocalDate parse(String text) {
        try {
            return text == null ? null : LocalDate.parse(text, EVENT_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
