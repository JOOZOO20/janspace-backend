package com.janspace.event.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.application.event.EventVenueChanged;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventDiscoveryRepository;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.EventMatchCandidateRepository;
import com.janspace.event.domain.EventSource;
import com.janspace.event.domain.EventSourceChange;
import com.janspace.event.domain.EventSourceChangeRepository;
import com.janspace.event.domain.EventSourceRepository;
import com.janspace.event.domain.SourceChangeStatus;
import com.janspace.event.domain.Venue;
import com.janspace.event.domain.VenueRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 민감 필드 변경 확인 큐의 승인·거절. 승인은 한 트랜잭션에서 값 적용·UTC 재계산·회차 키 이동·같은 키의 발견/후보 DISMISSED·이벤트 발행.
 * 거절된 값은 REJECTED 행이 남아 있는 동안 다음 접기에서 억제된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceChangeReviewUseCase {

    private final EventSourceChangeRepository changeRepository;
    private final EventSourceRepository sourceRepository;
    private final EventDiscoveryRepository discoveryRepository;
    private final EventMatchCandidateRepository candidateRepository;
    private final VenueRepository venueRepository;
    private final RunningTimeBorrowers runningTimeBorrowers;
    private final ApplicationEventPublisher publisher;

    @Transactional(readOnly = true)
    public List<EventSourceChange> list(SourceChangeStatus status) {
        return changeRepository.findAllByStatusOrderByDetectedAtDescIdDesc(status);
    }

    @Transactional
    public void approve(Long changeId) {
        EventSourceChange change = loadPending(changeId);
        Event event = change.getEvent();
        Instant now = Instant.now();
        // 적용은 applySource 를 탄다: 제안 뒤 관리자가 그 필드를 잠갔다면 관리자 값이 이긴다
        switch (change.getField()) {
            case EVENT_DATE -> {
                String key = change.getProposedValue();
                LocalDate date = LocalDate.parse(key.substring(0, 10));
                event.applySource(EventLockableField.EVENT_DATE, date);
                sourceRepository.findByEventIdAndProvider(event.getId(), change.getProvider())
                        .ifPresent(source -> moveOccurrence(source, key, date, now));
                publisher.publishEvent(new EventScheduleChanged(event.getId()));
                // 같은 시즌 회차가 "가장 가까운 회차"로 빌려 쓰던 공연 시간이 바뀔 수 있다
                if (event.getRunningMinutes() != null) {
                    runningTimeBorrowers.republish(event.getSeason(), event.getId());
                }
            }
            case STARTS_AT -> {
                event.applySource(EventLockableField.STARTS_AT, LocalDateTime.parse(change.getProposedValue()));
                publisher.publishEvent(new EventScheduleChanged(event.getId()));
            }
            case VENUE -> {
                Venue venue = venueRepository.findById(Long.parseLong(change.getProposedValue()))
                        .orElseThrow(() -> new NotFoundException(ErrorCode.VENUE_NOT_FOUND));
                event.applySource(EventLockableField.VENUE, venue);
                if (VenueCommand.syncTimezoneFromVenue(event)) {
                    publisher.publishEvent(new EventScheduleChanged(event.getId()));
                }
                publisher.publishEvent(new EventVenueChanged(event.getId()));
            }
        }
        change.approve(now);
        log.info("[출처 변경 승인] changeId={}, eventId={}, field={}, value={}", changeId, event.getId(), change.getField(),
                change.getProposedValue());
    }

    @Transactional
    public void reject(Long changeId) {
        EventSourceChange change = loadPending(changeId);
        change.reject(Instant.now());
        log.info("[출처 변경 거절] changeId={}, eventId={}, field={}", changeId, change.getEvent().getId(), change.getField());
    }

    // 회차 키 이동: 제안 값에 시각(#HH:mm)이 없으면 기존 키의 시각 부분을 유지한다. 같은 새 키의 발견·후보는 닫는다
    private void moveOccurrence(EventSource source, String proposedKey, LocalDate date, Instant now) {
        String oldKey = source.getOccurrenceKey();
        String newKey;
        if (proposedKey.contains("#") || !oldKey.contains("#")) {
            newKey = proposedKey;
        } else {
            newKey = date + oldKey.substring(oldKey.indexOf('#'));
        }
        discoveryRepository.findByProviderAndExternalIdAndOccurrenceKey(source.getProvider(), source.getExternalId(), newKey)
                .filter(d -> d.isPending()).ifPresent(d -> d.dismiss(now));
        candidateRepository.deleteAll(candidateRepository.findAllByProviderAndExternalIdAndOccurrenceKey(
                source.getProvider(), source.getExternalId(), newKey));
        source.moveOccurrence(newKey);
    }

    private EventSourceChange loadPending(Long changeId) {
        EventSourceChange change = changeRepository.findById(changeId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SOURCE_CHANGE_NOT_FOUND));
        if (!change.isPending()) {
            throw new ConflictException(ErrorCode.CONFLICT, "이미 처리된 변경 요청입니다.");
        }
        return change;
    }
}
