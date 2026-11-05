package com.stagelog.event.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.common.locale.LocalizedText;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.EventSnapshot.VenueSnapshot;
import com.stagelog.event.application.EventView.VenueView;
import com.stagelog.event.application.port.PosterStoragePort;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.EventTranslation;
import com.stagelog.event.domain.EventTranslationRepository;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.TranslationField;
import com.stagelog.event.domain.Venue;
import com.stagelog.event.domain.VenueTranslation;
import com.stagelog.event.domain.VenueTranslationRepository;
import com.stagelog.media.application.ImageSet;
import com.stagelog.media.application.MediaImages;
import com.stagelog.media.application.MediaOwnerType;
import java.time.Duration;
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
 * 행사 조회. find/findByIds/findByVenue/runningTime 은 다른 기능에 공개하는 계약(EventSnapshot·RunningTime), page/view 는 event API 용 읽기 모델이다.
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
    private final MediaImages mediaImages;

    public enum Tab {
        UPCOMING, PAST
    }

    public Optional<EventSnapshot> find(Long eventId) {
        return eventRepository.findByIdAndDeletedAtIsNull(eventId).map(e -> snapshot(e, sourceRepository.findAllByEventId(eventId)));
    }

    // 기록 알림 시각 추정용 공연 시간 (RunningTime). 아무것도 모를 때의 기본값은 호출 기능이 자기 정책으로 넘긴다
    public Optional<RunningTime> runningTime(Long eventId, Duration concertDefault, Duration otherDefault) {
        return eventRepository.findByIdAndDeletedAtIsNull(eventId).map(event -> {
            List<Event> siblings = event.getSeason() == null ? List.of()
                    : eventRepository.findAllBySeasonAndDeletedAtIsNull(event.getSeason()).stream()
                            .filter(e -> !e.getId().equals(eventId)).toList();
            return RunningTime.of(event, siblings, concertDefault, otherDefault);
        });
    }

    public List<EventSnapshot> findByIds(Collection<Long> eventIds) {
        return snapshots(eventRepository.findAllByIdInAndDeletedAtIsNull(eventIds));
    }

    public List<EventSnapshot> findByVenue(Long venueId) {
        return snapshots(eventRepository.findAllByVenueIdAndDeletedAtIsNull(venueId));
    }

    // [from, toExclusive) 범위의 행사 (setlist 시드·병합 스케줄 대상 선택용). 날짜 오름차순
    public List<EventSnapshot> findBetween(LocalDate from, LocalDate toExclusive) {
        Sort sort = Sort.by(Sort.Direction.ASC, "eventDate", "startsAtLocal", "id");
        return snapshots(eventRepository.findPage(from, toExclusive, PageRequest.of(0, Integer.MAX_VALUE, sort)).getContent());
    }

    // 제공자 이름("SETLISTFM" 등)으로 연결된 출처가 있는 행사 전부
    public List<EventSnapshot> findLinkedTo(String provider) {
        List<Long> ids = sourceRepository.findAllByProvider(Provider.valueOf(provider)).stream()
                .map(s -> s.getEvent().getId()).distinct().toList();
        return ids.isEmpty() ? List.of() : findByIds(ids);
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

    // 여러 행사의 읽기 모델 (attendance 별자리 목록 등). 삭제 행사는 빠지고 순서는 보장하지 않는다
    public List<EventView> viewsByIds(Collection<Long> eventIds) {
        return eventIds.isEmpty() ? List.of() : views(eventRepository.findAllByIdInAndDeletedAtIsNull(eventIds));
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
        Map<Long, ImageSet> images = mediaImages.of(MediaOwnerType.EVENT_POSTER, events.stream()
                .map(e -> new MediaImages.Original(e.getId(), e.getPosterKey(), posterUrl(e.getPosterKey()))).toList());

        return events.stream().map(event -> {
            List<EventTranslation> own = translations.getOrDefault(event.getId(), List.of());
            Map<SupportedLocale, String> names = new EnumMap<>(SupportedLocale.class);
            Map<SupportedLocale, String> descriptions = new EnumMap<>(SupportedLocale.class);
            own.forEach(t -> (t.getField() == TranslationField.NAME ? names : descriptions).put(t.getLocale(), t.getText()));
            LocalizedText description = event.getDescription() == null ? null : new LocalizedText(event.getDescription(), descriptions);
            Venue venue = event.getVenue();
            VenueView venueView = venue == null ? null
                    : venueView(venue, venueTranslations.getOrDefault(venue.getId(), List.of()));
            return new EventView(event.getId(), new LocalizedText(event.getName(), names), event.getType(), event.getStatus(),
                    event.getEventDate(), event.getStartsAtLocal(), event.getEndsAtLocal(), event.getTimezone(), venueView,
                    event.getSeason(), externalIds(sources.getOrDefault(event.getId(), List.of())),
                    posterUrl(event.getPosterKey()), images.get(event.getId()), event.getTicketUrl(), description, event.getRunningMinutes(),
                    event.getLockedFields());
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
