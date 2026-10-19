package com.stagelog.event.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 다른 기능에 공개하는 행사 스냅샷. 도메인 타입을 노출하지 않으므로 status 는 자체 enum, externalIds 는 제공자 이름 -> 외부 ID.
 */
public record EventSnapshot(Long id, LocalDate eventDate, LocalDateTime startsAtLocal, LocalDateTime endsAtLocal,
                            String timezone, Instant startsAt, Instant endsAt, Status status, VenueSnapshot venue,
                            Map<String, String> externalIds) {

    public enum Status {
        ACTIVE, CANCEL_SUSPECTED, CANCELLED
    }

    public record VenueSnapshot(Long id, String name, String city, String countryCode, Double lat, Double lng) {
    }
}
