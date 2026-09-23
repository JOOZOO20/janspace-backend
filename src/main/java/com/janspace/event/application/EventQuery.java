package com.janspace.event.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.common.locale.LocalizedText;
import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.application.EventSnapshot.VenueSnapshot;
import com.janspace.event.application.EventView.VenueView;
import com.janspace.event.application.port.PosterStoragePort;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventRepository;
import com.janspace.event.domain.EventSource;
import com.janspace.event.domain.EventSourceRepository;
import com.janspace.event.domain.EventTranslation;
import com.janspace.event.domain.EventTranslationRepository;
import com.janspace.event.domain.Provider;
import com.janspace.event.domain.TranslationField;
import com.janspace.event.domain.Venue;
import com.janspace.event.domain.VenueTranslation;
import com.janspace.event.domain.VenueTranslationRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 행사 조회. find/findByIds/findByVenue 는 다른 기능에 공개하는 계약(EventSnapshot, 설계), page/view 는 event API 용 읽기 모델이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventQuery {

    // 목록의 "오늘" 기준 시간대. 서비스 주 대상이 한국이라 고정한다
    public static final ZoneId LIST_ZONE = ZoneId.of("Asia/Seoul");

    private final EventRepository eventRepository;
    private final EventTranslationRepository translationRepository;
    private final VenueTranslationRepository venueTranslationRepository;
    private final EventSourceRepository sourceRepository;
    private final PosterStoragePort posterStoragePort;

    public enum Tab {
        UPCOMING, PAST
    }

    public Optional<EventSnapshot> find(Long eventId) {
        return eventRepository.findByIdAndDeletedAtIsNull(eventId).map(e -> snapshot(e, sourceRepository.findAllByEventId(eventId)));
    }

    public List<EventSnapshot> findByIds(Collection<Long> eventIds) {
        return snapshots(eventRepository.findAllByIdInAndDeletedAtIsNull(eventIds));
    }

    public List<EventSnapshot> findByVenue(Long venueId) {
        return snapshots(eventRepository.findAllByVenueIdAndDeletedAtIsNull(venueId));
    }

    // UPCOMING = 오늘(Asia/Seoul) 이후 오름차순, PAST = 오늘 이전 내림차순. year 는 event_date 연도 필터
    public Page<EventView> page(Tab tab, Integer year, int page, int size) {
        LocalDate today = LocalDate.now(LIST_ZONE);
        LocalDate from = null;
        LocalDate to = null;
        if (tab == Tab.UPCOMING) {
            from = today;
        } else {
            to = today;
        }
        if (year != null) {
            LocalDate yearStart = LocalDate.of(year, 1, 1);
            LocalDate yearEnd = yearStart.plusYears(1);
            from = from == null ? yearStart : max(from, yearStart);
            to = to == null ? yearEnd : min(to, yearEnd);
        }
        Sort.Direction direction = tab == Tab.UPCOMING ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort sort = Sort.by(direction, "eventDate", "startsAtLocal", "id");
        Page<Event> events = eventRepository.findPage(from, to, PageRequest.of(page, size, sort));
        return new PageImpl<>(views(events.getContent()), events.getPageable(), events.getTotalElements());
    }

    public EventView view(Long eventId) {
        Event event = eventRepository.findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
        return views(List.of(event)).get(0);
    }

    // 번역·출처를 일괄 조회해 N+1 없이 뷰를 만든다
    public List<EventView> views(List<Event> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        List<Long> ids = events.stream().map(Event::getId).toList();
        Map<Long, List<EventTranslation>> translations = new HashMap<>();
        translationRepository.findAllByEventIdIn(ids).forEach(t ->
                translations.computeIfAbsent(t.getEvent().getId(), k -> new java.util.ArrayList<>()).add(t));
        List<Long> venueIds = events.stream().map(Event::venueId).filter(Objects::nonNull).distinct().toList();
        Map<Long, List<VenueTranslation>> venueTranslations = new HashMap<>();
        if (!venueIds.isEmpty()) {
            venueTranslationRepository.findAllByVenueIdIn(venueIds).forEach(t ->
                    venueTranslations.computeIfAbsent(t.getVenue().getId(), k -> new java.util.ArrayList<>()).add(t));
        }
        Map<Long, List<EventSource>> sources = new HashMap<>();
        sourceRepository.findAllByEventIdIn(ids).forEach(s ->
                sources.computeIfAbsent(s.getEvent().getId(), k -> new java.util.ArrayList<>()).add(s));

        return events.stream().map(event -> {
            List<EventTranslation> own = translations.getOrDefault(event.getId(), List.of());
            Map<SupportedLocale, String> names = new EnumMap<>(SupportedLocale.class);
            own.stream().filter(t -> t.getField() == TranslationField.NAME).forEach(t -> names.put(t.getLocale(), t.getText()));
            Venue venue = event.getVenue();
            VenueView venueView = venue == null ? null
                    : venueView(venue, venueTranslations.getOrDefault(venue.getId(), List.of()));
            return new EventView(event.getId(), new LocalizedText(event.getName(), names), event.getType(), event.getStatus(),
                    event.getEventDate(), event.getStartsAtLocal(), event.getEndsAtLocal(), event.getTimezone(), venueView,
                    event.getSeason(), externalIds(sources.getOrDefault(event.getId(), List.of())),
                    posterUrl(event.getPosterKey()), event.getTicketUrl(), event.getLockedFields());
        }).toList();
    }

    private VenueView venueView(Venue venue, List<VenueTranslation> translations) {
        Map<SupportedLocale, String> names = new EnumMap<>(SupportedLocale.class);
        Map<SupportedLocale, String> cities = new EnumMap<>(SupportedLocale.class);
        for (VenueTranslation t : translations) {
            (t.getField() == TranslationField.NAME ? names : cities).put(t.getLocale(), t.getText());
        }
        LocalizedText city = venue.getCity() == null ? null : new LocalizedText(venue.getCity(), cities);
        return new VenueView(venue.getId(), new LocalizedText(venue.getName(), names), city, venue.getCountryCode(),
                venue.coordinates(), venue.getTimezone());
    }

    // poster_key 가 절대 URL 이면(제공자 포스터) 그대로, 아니면 S3 공개 URL
    private String posterUrl(String posterKey) {
        if (posterKey == null) {
            return null;
        }
        return posterKey.startsWith("http://") || posterKey.startsWith("https://") ? posterKey : posterStoragePort.publicUrl(posterKey);
    }

    private static Map<Provider, String> externalIds(List<EventSource> sources) {
        Map<Provider, String> ids = new EnumMap<>(Provider.class);
        sources.forEach(s -> ids.put(s.getProvider(), s.getExternalId()));
        return ids;
    }

    private List<EventSnapshot> snapshots(List<Event> events) {
        Map<Long, List<EventSource>> sources = new HashMap<>();
        if (!events.isEmpty()) {
            sourceRepository.findAllByEventIdIn(events.stream().map(Event::getId).toList()).forEach(s ->
                    sources.computeIfAbsent(s.getEvent().getId(), k -> new java.util.ArrayList<>()).add(s));
        }
        return events.stream().map(e -> snapshot(e, sources.getOrDefault(e.getId(), List.of()))).toList();
    }

    private static EventSnapshot snapshot(Event event, List<EventSource> sources) {
        Venue venue = event.getVenue();
        VenueSnapshot venueSnapshot = venue == null ? null
                : new VenueSnapshot(venue.getId(), venue.getName(), venue.getCity(), venue.getCountryCode(),
                        venue.getLat() == null ? null : venue.getLat().doubleValue(),
                        venue.getLng() == null ? null : venue.getLng().doubleValue());
        Map<String, String> externalIds = new HashMap<>();
        sources.forEach(s -> externalIds.put(s.getProvider().name(), s.getExternalId()));
        return new EventSnapshot(event.getId(), event.getEventDate(), event.getStartsAtLocal(), event.getEndsAtLocal(),
                event.getTimezone(), event.getStartsAt(), event.getEndsAt(), EventSnapshot.Status.valueOf(event.getStatus().name()),
                venueSnapshot, Map.copyOf(externalIds));
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
