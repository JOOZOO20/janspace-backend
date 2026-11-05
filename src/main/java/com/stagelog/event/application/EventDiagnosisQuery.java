package com.stagelog.event.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourceChange;
import com.stagelog.event.domain.EventSourceChangeRepository;
import com.stagelog.event.domain.EventSourcePolicy;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventSourcePolicy.SourceVenue;
import com.stagelog.event.domain.EventSourceRepository;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.SourceChangeField;
import com.stagelog.event.domain.SourceChangeStatus;
import com.stagelog.event.domain.Venue;
import com.stagelog.event.domain.VenueRepository;
import com.stagelog.event.domain.VenueSource;
import com.stagelog.event.domain.VenueSourceRepository;
import com.stagelog.ops.application.SyncRunQuery;
import com.stagelog.ops.application.SyncRunQuery.SyncRunSummary;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진단용 행사 출처 조회 (MCP 진단 Tool 과 관리자 화면). 읽기만 하며 접기·공연장 생성 같은 쓰기를 하지 않는다.
 * syncStatus: 출처별 최근 Snapshot 시각과 그 출처의 마지막 동기화 실행, PENDING 변경 확인 큐, Field lock.
 * compare: 한 필드의 출처별 값과 우선순위(EventSourcePolicy 의 표), 지금 값이 그 값인 이유(lock, 거절 이력, 확인 대기).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventDiagnosisQuery {

    private final EventRepository eventRepository;
    private final EventSourceRepository sourceRepository;
    private final EventSourceChangeRepository changeRepository;
    private final VenueRepository venueRepository;
    private final VenueSourceRepository venueSourceRepository;
    private final SourceSnapshotCodec codec;
    private final SyncRunQuery syncRunQuery;

    /** snapshotId 는 event_sources.id. lastRun 은 그 출처의 가장 최근 동기화 실행(행사 단위가 아니라 출처 단위)이며 없으면 null. */
    public record ProviderStatus(String provider, Long snapshotId, String externalId, Instant fetchedAt, SyncRunSummary lastRun) {
    }

    public record PendingChange(Long id, String field, String currentValue, String proposedValue, String provider,
                                Instant detectedAt) {
    }

    public record SyncStatus(Long eventId, String eventName, List<ProviderStatus> providers, List<PendingChange> pendingChanges,
                             List<String> lockedFields) {
    }

    /** 출처 하나가 준 값. rank 는 우선순위 순번(1 이 최우선, 0 은 이 필드에 쓰이지 않는 출처). VENUE 의 venueId 는 기존 공연장으로 해석될 때만 있다. */
    public record SourceValue(String provider, int rank, Long snapshotId, String value, Long venueId) {
    }

    /**
     * reason: NO_SOURCE_VALUE(출처가 값을 안 줌) / LOCKED(관리자 잠금) / PRIORITY(우선순위 값이 채택됨) / PENDING_REVIEW(확인 큐 대기) /
     * REJECTED(같은 값이 거절된 이력) / WAITING_EVENT_DATE(출처 시각의 날짜가 행사 날짜와 달라 날짜 변경이 먼저 풀려야 함) /
     * NOT_FOLDED(다음 접기에서 반영될 차이). sourceChangeId 는 PENDING_REVIEW·REJECTED 의 근거 행.
     */
    public record Comparison(Long eventId, String field, List<String> priority, boolean locked, String currentValue,
                             String pickedProvider, String pickedValue, String reason, Long sourceChangeId,
                             List<SourceValue> values) {
    }

    public SyncStatus syncStatus(Long eventId) {
        Event event = load(eventId);
        List<ProviderStatus> providers = sourceRepository.findAllByEventId(eventId).stream()
                .sorted(Comparator.comparing(EventSource::getProvider))
                .map(source -> new ProviderStatus(source.getProvider().name(), source.getId(), source.getExternalId(),
                        source.getFetchedAt(), syncRunQuery.latestOf(source.getProvider().name()).orElse(null)))
                .toList();
        List<PendingChange> pending = changeRepository.findAllByEventIdAndStatus(eventId, SourceChangeStatus.PENDING).stream()
                .map(c -> new PendingChange(c.getId(), c.getField().name(), c.getCurrentValue(), c.getProposedValue(),
                        c.getProvider().name(), c.getDetectedAt()))
                .toList();
        List<String> locked = event.getLockedFields().stream().map(Enum::name).sorted().toList();
        return new SyncStatus(eventId, event.getName(), providers, pending, locked);
    }

    public Comparison compare(Long eventId, String fieldName) {
        EventLockableField field = parseField(fieldName);
        List<Provider> priority = EventSourcePolicy.priority(field);
        Event event = load(eventId);

        // 1. 출처별 값 (우선순위 순, 우선순위 밖 출처는 뒤)
        List<SourceValue> values = new ArrayList<>();
        for (EventSource source : sourceRepository.findAllByEventId(eventId)) {
            SourceSnapshot snapshot = codec.read(source.getRaw());
            Object raw = EventSourcePolicy.sourceValue(field, snapshot);
            String value = raw instanceof SourceVenue venue ? venue.name() : raw == null ? null : raw.toString();
            Long venueId = raw instanceof SourceVenue venue && venue.name() != null ? resolveVenueId(source.getProvider(), venue) : null;
            int rank = priority.indexOf(source.getProvider()) + 1;
            values.add(new SourceValue(source.getProvider().name(), rank, source.getId(), blankToNull(value), venueId));
        }
        values.sort(Comparator.comparingInt((SourceValue v) -> v.rank() == 0 ? Integer.MAX_VALUE : v.rank()));

        // 2. 접기가 고를 값 = 우선순위 순 첫 non-null (EventSourcePolicy.pick 과 같은 규칙)
        Optional<SourceValue> picked = values.stream().filter(v -> v.rank() > 0 && v.value() != null).findFirst();
        String current = currentValue(event, field);
        boolean locked = event.isLocked(field);
        String pickedComparable = picked.map(v -> field == EventLockableField.VENUE
                ? (v.venueId() == null ? null : String.valueOf(v.venueId())) : v.value()).orElse(null);

        // 3. 지금 값의 이유
        String reason;
        Long changeId = null;
        if (picked.isEmpty()) {
            reason = "NO_SOURCE_VALUE";
        } else if (locked) {
            reason = "LOCKED";
        } else if (Objects.equals(pickedComparable, current)) {
            reason = "PRIORITY";
        } else {
            Optional<SourceChangeField> sensitive = sensitive(field);
            // findFirstByEventIdAndFieldAndStatusOrderByIdDesc 는 잠금 읽기(FOR UPDATE)라 읽기 전용 트랜잭션에서 쓸 수 없다. 최신 PENDING 을 직접 고른다
            Optional<EventSourceChange> pendingChange = sensitive.flatMap(f -> changeRepository
                    .findAllByEventIdAndStatus(eventId, SourceChangeStatus.PENDING).stream()
                    .filter(c -> c.getField() == f)
                    .max(Comparator.comparing(EventSourceChange::getId)));
            Optional<EventSourceChange> rejected = sensitive.flatMap(f -> changeRepository
                    .findAllByEventIdAndStatus(eventId, SourceChangeStatus.REJECTED).stream()
                    .filter(c -> c.getField() == f && c.getProposedValue().equals(pickedComparable))
                    .findFirst());
            if (rejected.isPresent()) {
                reason = "REJECTED";
                changeId = rejected.get().getId();
            } else if (pendingChange.isPresent()) {
                reason = "PENDING_REVIEW";
                changeId = pendingChange.get().getId();
            } else if (field == EventLockableField.STARTS_AT
                    && !LocalDateTime.parse(picked.get().value()).toLocalDate().equals(event.getEventDate())) {
                reason = "WAITING_EVENT_DATE";
            } else {
                reason = "NOT_FOLDED";
            }
        }
        return new Comparison(eventId, field.name(), priority.stream().map(Enum::name).toList(), locked, current,
                picked.map(SourceValue::provider).orElse(null), picked.map(SourceValue::value).orElse(null), reason, changeId,
                values);
    }

    // 쓰기 없이 공연장 해석: 출처 외부 ID 연결 -> 같은 이름 공연장. 둘 다 없으면 다음 접기가 새 공연장을 만든다 (null)
    private Long resolveVenueId(Provider provider, SourceVenue venue) {
        Optional<Long> linked = venue.externalId() == null ? Optional.empty()
                : venueSourceRepository.findByProviderAndExternalId(provider, venue.externalId()).map(VenueSource::getVenue).map(Venue::getId);
        return linked.or(() -> venueRepository.findFirstByNameIgnoreCase(venue.name().trim()).map(Venue::getId)).orElse(null);
    }

    private static String currentValue(Event event, EventLockableField field) {
        Object value = switch (field) {
            case NAME -> event.getName();
            case VENUE -> event.venueId();
            case EVENT_DATE -> event.getEventDate();
            case STARTS_AT -> event.getStartsAtLocal();
            case POSTER -> event.getPosterKey();
            case DESCRIPTION -> event.getDescription();
            case TICKET_URL -> event.getTicketUrl();
            case RUNNING_MINUTES -> event.getRunningMinutes();
            case ENDS_AT, TYPE, SEASON, TIMEZONE -> null;
        };
        return value == null ? null : value.toString();
    }

    private static Optional<SourceChangeField> sensitive(EventLockableField field) {
        return switch (field) {
            case EVENT_DATE -> Optional.of(SourceChangeField.EVENT_DATE);
            case STARTS_AT -> Optional.of(SourceChangeField.STARTS_AT);
            case VENUE -> Optional.of(SourceChangeField.VENUE);
            default -> Optional.empty();
        };
    }

    private static EventLockableField parseField(String name) {
        try {
            EventLockableField field = EventLockableField.valueOf(name == null ? "" : name.trim().toUpperCase());
            if (!EventSourcePolicy.priority(field).isEmpty()) {
                return field;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 400
        }
        throw new BadRequestException(ErrorCode.INVALID_INPUT,
                "출처 접기 대상 필드가 아닙니다 (NAME, VENUE, EVENT_DATE, STARTS_AT, POSTER, DESCRIPTION, TICKET_URL, RUNNING_MINUTES): " + name);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Event load(Long eventId) {
        return eventRepository.findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
    }
}
