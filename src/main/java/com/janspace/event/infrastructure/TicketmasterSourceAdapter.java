package com.janspace.event.infrastructure;

import com.janspace.event.application.port.TicketmasterPort;
import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import com.janspace.event.domain.EventSourcePolicy.SourceVenue;
import com.janspace.event.domain.GeoPoint;
import com.janspace.integration.ticketmaster.TicketmasterClient;
import com.janspace.integration.ticketmaster.TicketmasterEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Ticketmaster 이벤트 -> SourceSnapshot. 현지 날짜·시각(dates.start.localDate/localTime), 공연장 좌표·시간대, 예매 URL,
 * 포스터는 3:4 > 16:9 비율 중 가장 큰 이미지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketmasterSourceAdapter implements TicketmasterPort {

    private final TicketmasterClient client;

    @Override
    public boolean isEnabled() {
        return client.isEnabled();
    }

    @Override
    public List<SourceSnapshot> artistEvents() {
        List<SourceSnapshot> out = new ArrayList<>();
        for (TicketmasterEvent e : client.attractionEvents()) {
            LocalDate date = e.dates() == null || e.dates().start() == null ? null : parseDate(e.dates().start().localDate());
            if (e.id() == null || date == null) {
                log.warn("[Ticketmaster 이벤트 무시] id 또는 localDate 없음. id={}", e.id());
                continue;
            }
            LocalDateTime startsAt = startsAt(e.dates().start(), date);
            out.add(new SourceSnapshot(e.id(), date.toString(), e.name(), null, e.url(), bestImage(e.images()), venue(e),
                    startsAt, date, null));
        }
        return out;
    }

    private static LocalDateTime startsAt(TicketmasterEvent.Start start, LocalDate date) {
        if (Boolean.TRUE.equals(start.noSpecificTime()) || start.localTime() == null || start.localTime().isBlank()) {
            return null;
        }
        try {
            return date.atTime(LocalTime.parse(start.localTime()));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static SourceVenue venue(TicketmasterEvent e) {
        TicketmasterEvent.Venue v = e.firstVenue();
        if (v == null || v.name() == null || v.name().isBlank()) {
            return null;
        }
        GeoPoint point = null;
        if (v.location() != null && v.location().latitude() != null && v.location().longitude() != null) {
            try {
                point = new GeoPoint(Double.parseDouble(v.location().latitude()), Double.parseDouble(v.location().longitude()));
            } catch (IllegalArgumentException ignored) {
                // 좌표 문자열 오류는 좌표 없음
            }
        }
        String timezone = v.timezone() != null ? v.timezone() : e.dates() == null ? null : e.dates().timezone();
        return new SourceVenue(v.id(), v.name(), v.city() == null ? null : v.city().name(),
                v.country() == null ? null : v.country().countryCode(), point, timezone);
    }

    // 3_4 > 16_9 > 그 외, 같은 비율이면 너비가 큰 것
    static String bestImage(List<TicketmasterEvent.Image> images) {
        if (images == null) {
            return null;
        }
        return images.stream()
                .filter(i -> i.url() != null)
                .min(Comparator.comparingInt((TicketmasterEvent.Image i) -> ratioRank(i.ratio()))
                        .thenComparing(i -> -(i.width() == null ? 0 : i.width())))
                .map(TicketmasterEvent.Image::url)
                .orElse(null);
    }

    private static int ratioRank(String ratio) {
        return "3_4".equals(ratio) ? 0 : "16_9".equals(ratio) ? 1 : 2;
    }

    private static LocalDate parseDate(String text) {
        try {
            return text == null ? null : LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
