package com.stagelog.event.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 출처 접기 정책. 순수 클래스이며 Spring·저장소를 모른다.
 * 연결된 모든 출처를 고정된 필드별 우선순위로 항상 전체 접기 하므로 입력 순서와 무관하다.
 * 잠긴 필드는 건너뛴다. 비민감 필드는 즉시 적용, 민감 필드(EVENT_DATE/STARTS_AT/VENUE)는 null -> 값만 즉시 적용하고
 * 값 -> 다른 값은 확인 큐 제안으로 돌려준다. REJECTED 로 남은 같은 값은 다시 제안하지 않는다.
 *
 * 왜 "도착한 값으로 덮어쓰기"가 아니라 "고정 우선순위로 전체 다시 접기"인가: 제공자 네 곳은 하루 다른 시각에 따로 돌고 실패·재실행도
 * 제각각이다. 마지막에 온 값이 이기면 결과가 실행 순서에 달려 매번 바뀐다. 저장된 모든 출처를 같은 규칙으로 다시 접으면 결과가 입력
 * 집합만으로 정해져(순서 무관·멱등) "이 값은 어디서 왔나"를 우선순위 표 하나로 설명할 수 있다. 우선순위는 필드별로 그 값을 가장
 * 정확히 아는 출처 순이다(이름은 팬 운영 캘린더인 네이버, 공연장·좌표는 시설 DB 가 있는 KOPIS, 시각은 회차 단위인 Ticketmaster).
 *
 * 왜 민감 필드는 확인 큐인가: 날짜·시작 시각·공연장은 참석자 전원의 기록 알림 시각과 이동 거리를 바꾼다. 출처끼리 자주 어긋나고
 * (네이버는 비공식 API, KOPIS 는 등록 정보) 잘못 적용하면 되돌려도 이미 알림이 나갔을 수 있다. 그래서 빈칸 채움만 자동이고
 * 값 -> 다른 값은 사람이 한 번 본다. 비민감 필드(이름·포스터·설명)는 틀려도 다음 접기나 관리자 잠금으로 바로 고칠 수 있어 자동 적용한다.
 */
public final class EventSourcePolicy {

    static final List<Provider> NAME_PRIORITY = List.of(Provider.NAVER, Provider.KOPIS, Provider.TICKETMASTER);
    static final List<Provider> VENUE_PRIORITY = List.of(Provider.KOPIS, Provider.TICKETMASTER, Provider.SETLISTFM);
    static final List<Provider> EVENT_DATE_PRIORITY = List.of(Provider.NAVER, Provider.KOPIS, Provider.TICKETMASTER, Provider.SETLISTFM);
    static final List<Provider> STARTS_AT_PRIORITY = List.of(Provider.TICKETMASTER, Provider.KOPIS);
    static final List<Provider> POSTER_PRIORITY = List.of(Provider.KOPIS, Provider.TICKETMASTER);
    static final List<Provider> DESCRIPTION_PRIORITY = List.of(Provider.KOPIS);
    static final List<Provider> TICKET_URL_PRIORITY = List.of(Provider.TICKETMASTER, Provider.KOPIS);
    static final List<Provider> RUNNING_MINUTES_PRIORITY = List.of(Provider.KOPIS);

    private EventSourcePolicy() {
    }

    /** 출처가 준 공연장 정보. 위치는 좌표를 아는 제공자(KOPIS·Ticketmaster)만, timezone(IANA) 은 Ticketmaster 만 채운다. */
    public record SourceVenue(String externalId, String name, String city, String countryCode, GeoPoint location,
                              String timezone) {
    }

    /**
     * 제공자 응답을 정규화한 스냅샷. 제공자 어댑터(2C)가 만들고 event_sources.raw 에 그대로 저장된다.
     * startsAtLocal 은 공연장 현지 벽시계. 파싱에 실패한 필드는 null (= 그 제공자는 그 필드를 주지 않은 것으로 본다).
     */
    public record SourceSnapshot(String externalId, String occurrenceKey, String name, String description, String ticketUrl,
                                 String posterUrl, SourceVenue venue, LocalDateTime startsAtLocal, LocalDate eventDate,
                                 Integer runningMinutes) {
        // 포스터만 바꾼 사본 (제공자 URL -> S3 key 재저장, key 백필)
        public SourceSnapshot withPosterUrl(String posterUrl) {
            return new SourceSnapshot(externalId, occurrenceKey, name, description, ticketUrl, posterUrl, venue, startsAtLocal,
                    eventDate, runningMinutes);
        }
    }

    /** 접기 대상 행사의 현재 값. */
    public record Current(String name, String description, String ticketUrl, String posterKey, Long venueId,
                          LocalDate eventDate, LocalDateTime startsAtLocal, Integer runningMinutes,
                          Set<EventLockableField> locked) {
        public static Current of(Event event) {
            return new Current(event.getName(), event.getDescription(), event.getTicketUrl(), event.getPosterKey(),
                    event.venueId(), event.getEventDate(), event.getStartsAtLocal(), event.getRunningMinutes(),
                    event.getLockedFields());
        }
    }

    /** 거절된 제안. 같은 (field, proposedValue) 는 억제한다. */
    public record Rejected(SourceChangeField field, String proposedValue) {
    }

    /** 확인 큐로 보낼 값 -> 다른 값 제안. 값 표기: EVENT_DATE = ISO 날짜, STARTS_AT = ISO 벽시계, VENUE = venueId. */
    public record Proposal(SourceChangeField field, String currentValue, String proposedValue, Provider provider) {
    }

    /**
     * 접기 결과. updates 는 즉시 적용할 (필드, 값). endsAtDraft 는 starts_at_local + running_minutes 초안이며 저장하지 않는다.
     */
    public record Fold(Map<EventLockableField, Object> updates, List<Proposal> proposals, LocalDateTime endsAtDraft) {
        public boolean isEmpty() {
            return updates.isEmpty() && proposals.isEmpty();
        }
    }

    private record Pick<T>(Provider provider, T value) {
    }

    public static Fold fold(Current current, Map<Provider, SourceSnapshot> sources, Map<Provider, Long> venueIds,
                            Collection<Rejected> rejected) {
        Map<EventLockableField, Object> updates = new EnumMap<>(EventLockableField.class);
        List<Proposal> proposals = new ArrayList<>();
        Set<Rejected> suppressed = new HashSet<>(rejected);
        Set<EventLockableField> locked = current.locked();

        // 1. 비민감 필드: 우선순위 첫 값이 현재와 다르면 즉시 갱신
        foldPlain(EventLockableField.NAME, NAME_PRIORITY, sources, SourceSnapshot::name, current.name(), locked, updates);
        foldPlain(EventLockableField.DESCRIPTION, DESCRIPTION_PRIORITY, sources, SourceSnapshot::description, current.description(), locked, updates);
        foldPlain(EventLockableField.TICKET_URL, TICKET_URL_PRIORITY, sources, SourceSnapshot::ticketUrl, current.ticketUrl(), locked, updates);
        foldPlain(EventLockableField.POSTER, POSTER_PRIORITY, sources, SourceSnapshot::posterUrl, current.posterKey(), locked, updates);
        foldPlain(EventLockableField.RUNNING_MINUTES, RUNNING_MINUTES_PRIORITY, sources, SourceSnapshot::runningMinutes, current.runningMinutes(), locked, updates);

        // 2. EVENT_DATE: 항상 값이 있으므로 다르면 언제나 제안
        if (!locked.contains(EventLockableField.EVENT_DATE)) {
            pick(EVENT_DATE_PRIORITY, sources, SourceSnapshot::eventDate).ifPresent(p -> {
                if (!p.value().equals(current.eventDate())) {
                    propose(proposals, suppressed, SourceChangeField.EVENT_DATE, current.eventDate().toString(),
                            p.value().toString(), p.provider());
                }
            });
        }

        // 3. STARTS_AT: 현재 행사 날짜의 시각만 접는다. 날짜가 다른 시각은 EVENT_DATE 제안이 먼저 풀려야 하므로 건너뛴다 (불변식)
        LocalDateTime effectiveStart = current.startsAtLocal();
        if (!locked.contains(EventLockableField.STARTS_AT)) {
            Optional<Pick<LocalDateTime>> pick = pick(STARTS_AT_PRIORITY, sources, SourceSnapshot::startsAtLocal)
                    .filter(p -> p.value().toLocalDate().equals(current.eventDate()));
            if (pick.isPresent()) {
                Pick<LocalDateTime> p = pick.get();
                if (current.startsAtLocal() == null) {
                    updates.put(EventLockableField.STARTS_AT, p.value());
                    effectiveStart = p.value();
                } else if (!p.value().equals(current.startsAtLocal())) {
                    propose(proposals, suppressed, SourceChangeField.STARTS_AT, current.startsAtLocal().toString(),
                            p.value().toString(), p.provider());
                }
            }
        }

        // 4. VENUE: 제공자별로 해석된 venueId 를 우선순위로 접는다
        if (!locked.contains(EventLockableField.VENUE)) {
            Optional<Pick<Long>> pick = VENUE_PRIORITY.stream()
                    .filter(sources::containsKey)
                    .filter(p -> venueIds.get(p) != null)
                    .findFirst()
                    .map(p -> new Pick<>(p, venueIds.get(p)));
            if (pick.isPresent()) {
                Pick<Long> p = pick.get();
                if (current.venueId() == null) {
                    updates.put(EventLockableField.VENUE, p.value());
                } else if (!p.value().equals(current.venueId())) {
                    propose(proposals, suppressed, SourceChangeField.VENUE, String.valueOf(current.venueId()),
                            String.valueOf(p.value()), p.provider());
                }
            }
        }

        // 5. ends_at 초안: 출처는 제안하지 않고 관리자 화면에 보여주기만 한다
        Integer minutes = updates.containsKey(EventLockableField.RUNNING_MINUTES)
                ? (Integer) updates.get(EventLockableField.RUNNING_MINUTES) : current.runningMinutes();
        LocalDateTime endsAtDraft = effectiveStart != null && minutes != null ? effectiveStart.plusMinutes(minutes) : null;

        return new Fold(updates, List.copyOf(proposals), endsAtDraft);
    }

    /**
     * 필드별 출처 우선순위. 진단 API(출처 비교)가 접기와 같은 표를 보여 주도록 공개한다. 접기 대상이 아닌 필드(ENDS_AT·TYPE·SEASON·TIMEZONE)는 빈 목록.
     */
    public static List<Provider> priority(EventLockableField field) {
        return switch (field) {
            case NAME -> NAME_PRIORITY;
            case VENUE -> VENUE_PRIORITY;
            case EVENT_DATE -> EVENT_DATE_PRIORITY;
            case STARTS_AT -> STARTS_AT_PRIORITY;
            case POSTER -> POSTER_PRIORITY;
            case DESCRIPTION -> DESCRIPTION_PRIORITY;
            case TICKET_URL -> TICKET_URL_PRIORITY;
            case RUNNING_MINUTES -> RUNNING_MINUTES_PRIORITY;
            case ENDS_AT, TYPE, SEASON, TIMEZONE -> List.of();
        };
    }

    /** 스냅샷이 그 필드에 준 값. fold 가 읽는 것과 같은 필드다. 접기 대상이 아닌 필드는 null. */
    public static Object sourceValue(EventLockableField field, SourceSnapshot snapshot) {
        return switch (field) {
            case NAME -> snapshot.name();
            case VENUE -> snapshot.venue();
            case EVENT_DATE -> snapshot.eventDate();
            case STARTS_AT -> snapshot.startsAtLocal();
            case POSTER -> snapshot.posterUrl();
            case DESCRIPTION -> snapshot.description();
            case TICKET_URL -> snapshot.ticketUrl();
            case RUNNING_MINUTES -> snapshot.runningMinutes();
            case ENDS_AT, TYPE, SEASON, TIMEZONE -> null;
        };
    }

    /** 회차 키 차이. (provider, external_id) 하나에서 사라진 키 1개 + 나타난 키 1개면 이동. */
    public record OccurrenceDiff(Set<String> vanished, Set<String> appeared) {
        public boolean isMove() {
            return vanished.size() == 1 && appeared.size() == 1;
        }

        public String movedFrom() {
            return vanished.iterator().next();
        }

        public String movedTo() {
            return appeared.iterator().next();
        }
    }

    public static OccurrenceDiff diffOccurrences(Set<String> known, Set<String> observed) {
        Set<String> vanished = new HashSet<>(known);
        vanished.removeAll(observed);
        Set<String> appeared = new HashSet<>(observed);
        appeared.removeAll(known);
        return new OccurrenceDiff(Set.copyOf(vanished), Set.copyOf(appeared));
    }

    private static <T> void foldPlain(EventLockableField field, List<Provider> priority, Map<Provider, SourceSnapshot> sources,
                                      Function<SourceSnapshot, T> getter, T currentValue, Set<EventLockableField> locked,
                                      Map<EventLockableField, Object> updates) {
        if (locked.contains(field)) {
            return;
        }
        pick(priority, sources, getter)
                .filter(p -> !p.value().equals(currentValue))
                .ifPresent(p -> updates.put(field, p.value()));
    }

    // 우선순위 순서로 첫 non-null 값
    private static <T> Optional<Pick<T>> pick(List<Provider> priority, Map<Provider, SourceSnapshot> sources,
                                              Function<SourceSnapshot, T> getter) {
        for (Provider provider : priority) {
            SourceSnapshot snapshot = sources.get(provider);
            if (snapshot == null) {
                continue;
            }
            T value = getter.apply(snapshot);
            if (value != null && !(value instanceof String s && s.isBlank())) {
                return Optional.of(new Pick<>(provider, value));
            }
        }
        return Optional.empty();
    }

    private static void propose(List<Proposal> proposals, Set<Rejected> suppressed, SourceChangeField field,
                                String currentValue, String proposedValue, Provider provider) {
        if (suppressed.contains(new Rejected(field, proposedValue))) {
            return;
        }
        proposals.add(new Proposal(field, currentValue, proposedValue, provider));
    }
}
