package com.janspace.event.application;

import com.janspace.common.locale.LocalizedText;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.EventStatus;
import com.janspace.event.domain.EventType;
import com.janspace.event.domain.GeoPoint;
import com.janspace.event.domain.Provider;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * API 응답용 읽기 모델. 번역을 LocalizedText 로 합쳐 둔다 (event 기능 내부용, 다른 기능은 EventSnapshot 을 쓴다).
 */
public record EventView(Long id, LocalizedText name, EventType type, EventStatus status, LocalDate eventDate,
                        LocalDateTime startsAtLocal, LocalDateTime endsAtLocal, String timezone, VenueView venue,
                        String season, Map<Provider, String> externalIds, String posterUrl, String ticketUrl,
                        LocalizedText description, Integer runningMinutes, Set<EventLockableField> lockedFields) {

    public record VenueView(Long id, LocalizedText name, LocalizedText city, String countryCode, GeoPoint location,
                            String timezone) {
    }
}
