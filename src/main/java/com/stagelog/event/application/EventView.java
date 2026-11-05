package com.stagelog.event.application;

import com.stagelog.common.locale.LocalizedText;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventStatus;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.event.domain.Provider;
import com.stagelog.media.application.ImageSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * API 응답용 읽기 모델. images 는 포스터의 화면별 rendition URL(변환 전 자리는 원본 URL), 포스터가 없으면 null.
 * 번역을 LocalizedText 로 합쳐 둔다 (event 기능 내부용, 다른 기능은 EventSnapshot 을 쓴다).
 */
public record EventView(Long id, LocalizedText name, EventType type, EventStatus status, LocalDate eventDate,
                        LocalDateTime startsAtLocal, LocalDateTime endsAtLocal, String timezone, VenueView venue,
                        String season, Map<Provider, String> externalIds, String posterUrl, ImageSet images, String ticketUrl,
                        LocalizedText description, Integer runningMinutes, Set<EventLockableField> lockedFields) {

    public record VenueView(Long id, LocalizedText name, LocalizedText city, String countryCode, GeoPoint location,
                            String timezone) {
    }
}
