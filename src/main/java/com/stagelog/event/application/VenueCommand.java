package com.stagelog.event.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.event.EventScheduleChanged;
import com.stagelog.event.application.event.VenueCoordinatesChanged;
import com.stagelog.event.application.port.GeocodingPort;
import com.stagelog.event.application.port.TimezonePort;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSourcePolicy.SourceVenue;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.TranslationField;
import com.stagelog.event.domain.Venue;
import com.stagelog.event.domain.VenueLockableField;
import com.stagelog.event.domain.VenueRepository;
import com.stagelog.event.domain.VenueSource;
import com.stagelog.event.domain.VenueSourceRepository;
import com.stagelog.event.domain.VenueTranslation;
import com.stagelog.event.domain.VenueTranslationRepository;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 공연장 생성·출처 해석·관리자 수정. 좌표 최초 채움과 관리자 수정은 VenueCoordinatesChanged, 시간대 변경은 잠기지 않은 행사에 전파해
 * 행사마다 EventScheduleChanged 를 발행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueCommand {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final VenueRepository venueRepository;
    private final VenueSourceRepository venueSourceRepository;
    private final VenueTranslationRepository venueTranslationRepository;
    private final EventRepository eventRepository;
    private final TimezonePort timezonePort;
    private final GeocodingPort geocodingPort;
    private final TranslationDrafts translationDrafts;
    private final ApplicationEventPublisher publisher;

    /** 관리자 입력 공연장. location 이 없으면 지오코딩한다. */
    public record VenueInput(LocalizedText name, LocalizedText city, String countryCode, GeoPoint location) {
    }

    /** 관리자 부분 수정. null 은 "변경 없음". */
    public record VenueUpdate(String name, String city, GeoPoint location, String timezone) {
    }

    // 출처가 준 공연장을 내부 공연장으로 해석한다: (provider, external_id) -> 이름 일치 -> 신규 생성
    // 한계: 이름 정확 일치만 본다. 표기 변형(지점명·괄호) 매칭이 필요해지면 정규화 규칙을 여기에 둔다
    @Transactional
    public Venue resolveFromSource(Provider provider, SourceVenue source) {
        Objects.requireNonNull(source.name(), "출처 공연장 이름이 없습니다");
        String raw = MAPPER.writeValueAsString(source);
        Optional<VenueSource> linked = source.externalId() == null ? Optional.empty()
                : venueSourceRepository.findByProviderAndExternalId(provider, source.externalId());
        Venue venue;
        if (linked.isPresent()) {
            venue = linked.get().getVenue();
            linked.get().updateRaw(raw);
        } else {
            venue = venueRepository.findFirstByNameIgnoreCase(source.name().trim())
                    .orElseGet(() -> venueRepository.save(
                            Venue.create(source.name(), localeFor(source.countryCode()), source.city(), source.countryCode())));
            if (source.externalId() != null) {
                venueSourceRepository.save(new VenueSource(venue, provider, source.externalId(), raw));
            }
        }
        venue.fillDetails(source.city(), source.countryCode());
        // 제공자가 시간대를 직접 주면(Ticketmaster) 좌표 조회보다 먼저 채운다. 유효하지 않은 값은 무시
        if (source.timezone() != null && isValidZone(source.timezone()) && venue.fillTimezone(source.timezone())) {
            propagateTimezone(venue);
        }
        fillCoordinatesAndTimezone(venue, source.location());
        return venue;
    }

    // 관리자 입력: 같은 이름이 있으면 재사용, 없으면 생성(지오코딩·시간대·번역 초안)
    @Transactional
    public Venue createOrReuse(VenueInput input) {
        Objects.requireNonNull(input.name(), "공연장 이름이 필요합니다");
        String name = input.name().original().trim();
        Venue venue = venueRepository.findFirstByNameIgnoreCase(name).orElse(null);
        if (venue == null) {
            String city = input.city() == null ? null : input.city().original();
            venue = venueRepository.save(Venue.create(name, localeFor(input.countryCode()), city, input.countryCode()));
            saveTranslations(venue, TranslationField.NAME, input.name());
            if (input.city() != null) {
                saveTranslations(venue, TranslationField.CITY, input.city());
            }
        }
        GeoPoint location = input.location();
        if (location == null && venue.coordinates() == null) {
            // 외부 호출 (Kakao / LocationIQ). 실패는 좌표 없음으로 두고 관리자가 나중에 채운다
            String query = input.city() == null ? name : name + " " + input.city().original();
            try {
                location = geocodingPort.geocode(query, input.countryCode()).orElse(null);
            } catch (RuntimeException e) {
                log.warn("[지오코딩 실패] venueId={}, query={}", venue.getId(), query, e);
            }
        }
        fillCoordinatesAndTimezone(venue, location);
        return venue;
    }

    @Transactional
    public void adminUpdate(Long venueId, VenueUpdate update) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.VENUE_NOT_FOUND));
        if (update.name() != null) {
            venue.adminUpdate(VenueLockableField.NAME, update.name());
        }
        if (update.city() != null) {
            venue.adminUpdate(VenueLockableField.CITY, update.city());
        }
        if (update.location() != null) {
            // 좌표는 확인 큐 없이 관리자만 바꾼다. 변경마다 참석자 거리 체인 재계산 대상
            if (venue.adminUpdate(VenueLockableField.COORDINATES, update.location())) {
                publisher.publishEvent(new VenueCoordinatesChanged(venue.getId()));
            }
        }
        if (update.timezone() != null) {
            if (venue.adminUpdate(VenueLockableField.TIMEZONE, update.timezone())) {
                propagateTimezone(venue);
            }
        }
        log.info("[공연장 관리자 수정] venueId={}, locked={}", venueId, venue.getLockedFields());
    }

    // 공연장 시간대를 TIMEZONE 이 잠기지 않은 행사에 전파. 시간대가 바뀐 행사마다 EventScheduleChanged (호출자 트랜잭션 안)
    private void propagateTimezone(Venue venue) {
        if (venue.getTimezone() == null) {
            return;
        }
        for (Event event : eventRepository.findAllByVenueIdAndDeletedAtIsNull(venue.getId())) {
            if (syncTimezoneFromVenue(event)) {
                publisher.publishEvent(new EventScheduleChanged(event.getId()));
            }
        }
    }

    // 행사 timezone 접기: ADMIN(잠금) > 공연장 timezone > 기본값. 시간대가 바뀌었으면 true.
    // 시각이 없는 행사도 UTC 변화는 없지만 기록 알림의 당일 현지 시각이 시간대를 따르므로 일정 변경으로 본다
    public static boolean syncTimezoneFromVenue(Event event) {
        Venue venue = event.getVenue();
        if (venue == null || venue.getTimezone() == null || event.isLocked(EventLockableField.TIMEZONE)) {
            return false;
        }
        return event.applySource(EventLockableField.TIMEZONE, venue.getTimezone());
    }

    private void fillCoordinatesAndTimezone(Venue venue, GeoPoint location) {
        if (venue.fillCoordinates(location)) {
            publisher.publishEvent(new VenueCoordinatesChanged(venue.getId()));
        }
        GeoPoint point = venue.coordinates();
        if (venue.getTimezone() == null && point != null) {
            timezonePort.resolve(point.lat(), point.lng()).ifPresent(zone -> {
                if (venue.fillTimezone(zone.getId())) {
                    propagateTimezone(venue);
                }
            });
        }
    }

    private void saveTranslations(Venue venue, TranslationField field, LocalizedText text) {
        TranslationDrafts.Drafted drafted = translationDrafts.of(text, venue.getOriginalLocale());
        drafted.texts().forEach((locale, value) -> venueTranslationRepository.save(
                new VenueTranslation(venue, field, locale, value, drafted.source(), drafted.reviewed())));
    }

    private static boolean isValidZone(String zone) {
        try {
            java.time.ZoneId.of(zone);
            return true;
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    static SupportedLocale localeFor(String countryCode) {
        if (countryCode == null) {
            return SupportedLocale.KO;
        }
        return switch (countryCode.toUpperCase()) {
            case "KR" -> SupportedLocale.KO;
            case "JP" -> SupportedLocale.JA;
            case "TW" -> SupportedLocale.ZH_TW;
            default -> SupportedLocale.EN;
        };
    }
}
