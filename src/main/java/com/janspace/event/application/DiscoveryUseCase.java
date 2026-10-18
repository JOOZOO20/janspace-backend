package com.janspace.event.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.domain.DiscoveryStatus;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventDiscovery;
import com.janspace.event.domain.EventDiscoveryRepository;
import com.janspace.event.domain.EventRepository;
import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import com.janspace.event.domain.EventType;
import com.janspace.event.domain.Provider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발견 행사 처리: 수락(새 행사 생성 또는 기존 행사에 연결) · 무시.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscoveryUseCase {

    private final EventDiscoveryRepository discoveryRepository;
    private final EventRepository eventRepository;
    private final ApplySourcesUseCase applySourcesUseCase;
    private final SourceSnapshotCodec codec;
    private final ApplicationEventPublisher publisher;

    /** 관리자 화면용. snapshot 은 raw 를 복원한 것. */
    public record DiscoveryView(Long id, Provider provider, String externalId, String occurrenceKey, DiscoveryStatus status,
                                Instant createdAt, Long eventId, SourceSnapshot snapshot) {
    }

    @Transactional(readOnly = true)
    public List<DiscoveryView> all() {
        return discoveryRepository.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(d -> new DiscoveryView(d.getId(), d.getProvider(), d.getExternalId(), d.getOccurrenceKey(), d.getStatus(),
                        d.getCreatedAt(), d.getEvent() == null ? null : d.getEvent().getId(), codec.read(d.getRaw())))
                .toList();
    }

    // eventId 없음 = 초안으로 새 행사 생성, 있음 = 그 행사에 출처로 연결. 둘 다 곧바로 접기까지 한다
    @Transactional
    public Long accept(Long discoveryId, Long eventId) {
        EventDiscovery discovery = loadPending(discoveryId);
        SourceSnapshot snapshot = codec.read(discovery.getRaw());
        Event event;
        if (eventId == null) {
            String name = snapshot.name() == null || snapshot.name().isBlank() ? "(이름 없음)" : snapshot.name();
            // 종류는 알 수 없으므로 CONCERT 로 두고 관리자가 고친다
            event = eventRepository.save(Event.create(name, EventCommand.ADMIN_LOCALE, EventType.CONCERT, discovery.getEventDate()));
            publisher.publishEvent(new EventScheduleChanged(event.getId()));
        } else {
            event = eventRepository.findByIdAndDeletedAtIsNull(eventId)
                    .orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
        }
        applySourcesUseCase.apply(event.getId(), Map.of(discovery.getProvider(), snapshot));
        discovery.accept(event, Instant.now());
        log.info("[발견 수락] discoveryId={}, eventId={}, created={}", discoveryId, event.getId(), eventId == null);
        return event.getId();
    }

    @Transactional
    public void dismiss(Long discoveryId) {
        loadPending(discoveryId).dismiss(Instant.now());
        log.info("[발견 무시] discoveryId={}", discoveryId);
    }

    private EventDiscovery loadPending(Long discoveryId) {
        EventDiscovery discovery = discoveryRepository.findById(discoveryId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.DISCOVERY_NOT_FOUND));
        if (!discovery.isPending()) {
            throw new ConflictException(ErrorCode.CONFLICT, "이미 처리된 발견 행사입니다.");
        }
        return discovery;
    }
}
