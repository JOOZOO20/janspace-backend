package com.janspace.event.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.application.event.EventVenueChanged;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventDiscovery;
import com.janspace.event.domain.EventDiscoveryRepository;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.EventRepository;
import com.janspace.event.domain.EventSource;
import com.janspace.event.domain.EventSourceChange;
import com.janspace.event.domain.EventSourceChangeRepository;
import com.janspace.event.domain.EventSourcePolicy;
import com.janspace.event.domain.EventSourcePolicy.Fold;
import com.janspace.event.domain.EventSourcePolicy.OccurrenceDiff;
import com.janspace.event.domain.EventSourcePolicy.Proposal;
import com.janspace.event.domain.EventSourcePolicy.Rejected;
import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import com.janspace.event.domain.EventSourceRepository;
import com.janspace.event.domain.EventStatus;
import com.janspace.event.domain.Provider;
import com.janspace.event.domain.SourceChangeField;
import com.janspace.event.domain.SourceChangeStatus;
import com.janspace.event.domain.Venue;
import com.janspace.event.domain.VenueRepository;
import com.janspace.ops.application.OperatorAlert;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출처 스냅샷을 행사에 연결하고 EventSourcePolicy 로 접는다. 즉시 갱신은 저장, 민감 변경은 (event, field) 당 PENDING 1건으로 upsert 하고
 * 새 값마다 운영자 알림 1회. 회차 키 차이(날짜 이동·취소 의심·발견)도 여기서 판정한다.
 * 제공자 응답을 SourceSnapshot 으로 바꾸는 것은 2C 동기화 어댑터의 몫이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplySourcesUseCase {

    private final EventRepository eventRepository;
    private final EventSourceRepository eventSourceRepository;
    private final EventSourceChangeRepository changeRepository;
    private final EventDiscoveryRepository discoveryRepository;
    private final VenueRepository venueRepository;
    private final VenueCommand venueCommand;
    private final SourceSnapshotCodec codec;
    private final OperatorAlert operatorAlert;
    private final RunningTimeBorrowers runningTimeBorrowers;
    private final ApplicationEventPublisher publisher;

    /** 회차 키 차이 처리 결과. */
    public record Reconciliation(boolean moveProposed, List<Long> suspectedEventIds, List<Long> discoveryIds) {
    }

    // 스냅샷을 출처로 연결(행사당 제공자별 1건 upsert)한 뒤 접는다
    @Transactional
    public Fold apply(Long eventId, Map<Provider, SourceSnapshot> snapshots) {
        Event event = load(eventId);
        Instant now = Instant.now();
        snapshots.forEach((provider, snapshot) -> {
            String raw = codec.write(snapshot);
            eventSourceRepository.findByEventIdAndProvider(eventId, provider).ifPresentOrElse(
                    source -> source.refresh(snapshot.externalId(), snapshot.occurrenceKey(), raw, now),
                    () -> eventSourceRepository.save(new EventSource(event, provider, snapshot.externalId(),
                            snapshot.occurrenceKey(), raw, now)));
        });
        return fold(event, snapshots);
    }

    // 저장된 출처만으로 다시 접는다 (잠금 해제 직후)
    @Transactional
    public Fold refold(Long eventId) {
        Event event = load(eventId);
        Map<Provider, SourceSnapshot> snapshots = new EnumMap<>(Provider.class);
        for (EventSource source : eventSourceRepository.findAllByEventId(eventId)) {
            snapshots.put(source.getProvider(), codec.read(source.getRaw()));
        }
        return fold(event, snapshots);
    }

    /**
     * (provider, external_id) 하나의 이번 동기화 관측 키를 알려진 키와 비교한다.
     * 사라진 1 + 나타난 1 = 이동 제안(PENDING EVENT_DATE) / 사라진 키만 = CANCEL_SUSPECTED / 나타난 키만 = 발견.
     * 그대로인 키의 스냅샷 갱신은 호출자가 apply 로 한다.
     */
    @Transactional
    public Reconciliation reconcileOccurrences(Provider provider, String externalId, Map<String, SourceSnapshot> observed) {
        Map<String, EventSource> known = new HashMap<>();
        for (EventSource source : eventSourceRepository.findAllByProviderAndExternalId(provider, externalId)) {
            known.put(source.getOccurrenceKey(), source);
        }
        OccurrenceDiff diff = EventSourcePolicy.diffOccurrences(known.keySet(), observed.keySet());
        Instant now = Instant.now();

        // 케이스 A: 이동. 확인 큐 1건, 발견 행 없음, 취소 의심 전환 없음
        if (diff.isMove()) {
            Event event = known.get(diff.movedFrom()).getEvent();
            if (isRejected(event, SourceChangeField.EVENT_DATE, diff.movedTo()) || event.isLocked(EventLockableField.EVENT_DATE)) {
                return new Reconciliation(false, List.of(), List.of());
            }
            upsertPending(event, new Proposal(SourceChangeField.EVENT_DATE, diff.movedFrom(), diff.movedTo(), provider), now);
            return new Reconciliation(true, List.of(), List.of());
        }

        // 케이스 B: 사라진 키 -> 취소 의심 (활성 행사만)
        List<Long> suspected = new ArrayList<>();
        for (String key : diff.vanished()) {
            Event event = known.get(key).getEvent();
            if (event.getStatus() == EventStatus.ACTIVE && !event.isDeleted()) {
                event.changeStatus(EventStatus.CANCEL_SUSPECTED);
                suspected.add(event.getId());
            }
        }

        // 케이스 C: 나타난 키 -> 발견. 이미 있거나 거절된 키는 억제
        List<Long> discovered = new ArrayList<>();
        for (String key : diff.appeared()) {
            if (discoveryRepository.findByProviderAndExternalIdAndOccurrenceKey(provider, externalId, key).isPresent()
                    || known.values().stream().anyMatch(s -> isRejected(s.getEvent(), SourceChangeField.EVENT_DATE, key))) {
                continue;
            }
            SourceSnapshot snapshot = observed.get(key);
            LocalDate date = snapshot.eventDate() != null ? snapshot.eventDate() : LocalDate.parse(key.substring(0, 10));
            String venueName = snapshot.venue() == null ? null : snapshot.venue().name();
            String city = snapshot.venue() == null ? null : snapshot.venue().city();
            discovered.add(discoveryRepository.save(
                    new EventDiscovery(provider, externalId, key, codec.write(snapshot), date, venueName, city)).getId());
        }
        if (!suspected.isEmpty() || !discovered.isEmpty()) {
            operatorAlert.send("[행사 출처 변화] provider=" + provider + ", externalId=" + externalId
                    + ", 취소 의심 eventIds=" + suspected + ", 새 발견 discoveryIds=" + discovered);
        }
        log.info("[회차 키 비교] provider={}, externalId={}, vanished={}, appeared={}", provider, externalId,
                diff.vanished(), diff.appeared());
        return new Reconciliation(false, suspected, discovered);
    }

    private Fold fold(Event event, Map<Provider, SourceSnapshot> snapshots) {
        // 1. 출처 공연장 해석 (좌표 최초 채움·시간대 포함)
        Map<Provider, Long> venueIds = new EnumMap<>(Provider.class);
        snapshots.forEach((provider, snapshot) -> {
            if (snapshot.venue() != null && snapshot.venue().name() != null) {
                venueIds.put(provider, venueCommand.resolveFromSource(provider, snapshot.venue()).getId());
            }
        });

        // 2. 거절된 제안은 억제
        List<Rejected> rejected = changeRepository.findAllByEventIdAndStatus(event.getId(), SourceChangeStatus.REJECTED)
                .stream().map(c -> new Rejected(c.getField(), c.getProposedValue())).toList();

        // 3. 접기
        Fold fold = EventSourcePolicy.fold(EventSourcePolicy.Current.of(event), snapshots, venueIds, rejected);

        // 4. 즉시 갱신 적용. 민감 필드의 null -> 값 채움과 공연 시간 변경(기록 알림 시각 추정)은 이벤트 발행 대상
        boolean scheduleChanged = false;
        boolean runningTimeChanged = false;
        boolean venueChanged = false;
        for (Map.Entry<EventLockableField, Object> update : fold.updates().entrySet()) {
            switch (update.getKey()) {
                case VENUE -> {
                    Venue venue = venueRepository.findById((Long) update.getValue())
                            .orElseThrow(() -> new NotFoundException(ErrorCode.VENUE_NOT_FOUND));
                    venueChanged |= event.applySource(EventLockableField.VENUE, venue);
                }
                case STARTS_AT -> scheduleChanged |= event.applySource(EventLockableField.STARTS_AT, update.getValue());
                case RUNNING_MINUTES -> runningTimeChanged |= event.applySource(EventLockableField.RUNNING_MINUTES, update.getValue());
                default -> event.applySource(update.getKey(), update.getValue());
            }
        }
        scheduleChanged |= VenueCommand.syncTimezoneFromVenue(event);
        if (scheduleChanged || runningTimeChanged) {
            publisher.publishEvent(new EventScheduleChanged(event.getId()));
        }
        if (runningTimeChanged) {
            runningTimeBorrowers.republish(event.getSeason(), event.getId());
        }
        if (venueChanged) {
            publisher.publishEvent(new EventVenueChanged(event.getId()));
        }

        // 5. 값 -> 다른 값 제안은 확인 큐로
        Instant now = Instant.now();
        for (Proposal proposal : fold.proposals()) {
            upsertPending(event, proposal, now);
        }
        log.info("[출처 접기] eventId={}, providers={}, updates={}, proposals={}", event.getId(), snapshots.keySet(),
                fold.updates().keySet(), fold.proposals().size());
        return fold;
    }

    // (event, field) 당 PENDING 1건. 새로 생기거나 값이 바뀌면 운영자 알림 1회
    private void upsertPending(Event event, Proposal proposal, Instant now) {
        Optional<EventSourceChange> existing = changeRepository
                .findByEventIdAndFieldAndStatus(event.getId(), proposal.field(), SourceChangeStatus.PENDING);
        boolean notify;
        if (existing.isPresent()) {
            notify = existing.get().propose(proposal.currentValue(), proposal.proposedValue(), proposal.provider(), now);
        } else {
            changeRepository.save(new EventSourceChange(event, proposal.field(), proposal.currentValue(),
                    proposal.proposedValue(), proposal.provider(), now));
            notify = true;
        }
        if (notify) {
            operatorAlert.send("[행사 출처 변경 확인 필요] eventId=" + event.getId() + ", field=" + proposal.field()
                    + ", " + proposal.currentValue() + " -> " + proposal.proposedValue() + " (" + proposal.provider() + ")");
        }
    }

    private boolean isRejected(Event event, SourceChangeField field, String proposedValue) {
        return changeRepository.existsByEventIdAndFieldAndStatusAndProposedValue(event.getId(), field,
                SourceChangeStatus.REJECTED, proposedValue);
    }

    private Event load(Long eventId) {
        return eventRepository.findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
    }
}
