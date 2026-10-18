package com.janspace.event.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.ConflictException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventDiscovery;
import com.janspace.event.domain.EventDiscoveryRepository;
import com.janspace.event.domain.EventMatchCandidateRepository;
import com.janspace.event.domain.EventProposalRepository;
import com.janspace.event.domain.EventRepository;
import com.janspace.event.domain.EventSource;
import com.janspace.event.domain.EventSourceChangeRepository;
import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import com.janspace.event.domain.EventSourceRepository;
import com.janspace.event.domain.EventTranslationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 행사 삭제 (물리 삭제). 행사 소유 하위 데이터(번역·출처·매칭 후보·확인 큐·팬 제안)는 함께 지운다.
 * 다른 기능의 사용자 데이터(참석·기록·셋리·티켓 등)가 참조하는 행사는 그 테이블의 외래 키(기본 RESTRICT)가 삭제를 막고, 이를 409 로 돌려준다.
 * 참조 판정을 DB 에 맡기므로 event 가 다른 기능을 조회하지 않고(역방향 의존 없음) 새로 생기는 참조 기능도 자동으로 포함된다.
 * 행사에서 파생된 데이터(푸시 잡)는 그 테이블이 ON DELETE CASCADE 로 따라 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeleteEventUseCase {

    private final EventRepository eventRepository;
    private final EventTranslationRepository translationRepository;
    private final EventSourceRepository sourceRepository;
    private final EventMatchCandidateRepository candidateRepository;
    private final EventSourceChangeRepository changeRepository;
    private final EventProposalRepository proposalRepository;
    private final EventDiscoveryRepository discoveryRepository;
    private final SourceSnapshotCodec codec;

    @Transactional
    public void delete(Long eventId) {
        Event event = eventRepository.findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
        Instant now = Instant.now();

        // 1. 연결됐던 외부 회차를 DISMISSED 발견으로 남긴다. 지우기만 하면 다음 동기화가 같은 회차로 행사를 다시 만들거나(네이버) 발견으로 다시 올린다
        discoveryRepository.findAllByEventId(eventId).forEach(d -> d.dismissForDeletedEvent(now));
        List<EventSource> sources = sourceRepository.findAllByEventId(eventId);
        for (EventSource source : sources) {
            discoveryRepository.findByProviderAndExternalIdAndOccurrenceKey(source.getProvider(), source.getExternalId(),
                    source.getOccurrenceKey()).ifPresentOrElse(d -> d.dismissForDeletedEvent(now), () -> suppress(source, now));
        }

        // 2. 행사 소유 하위 데이터
        translationRepository.deleteAllByEventId(eventId);
        sourceRepository.deleteAll(sources);
        candidateRepository.deleteAllByEventId(eventId);
        changeRepository.deleteAllByEventId(eventId);
        proposalRepository.deleteAllByEventId(eventId);

        // 3. 행사 삭제. flush 에서 다른 기능의 참조가 외래 키 위반으로 드러나면 전체를 되돌리고 409
        try {
            eventRepository.delete(event);
            eventRepository.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("[행사 삭제 거부] eventId={}: 다른 기능의 데이터가 참조 중", eventId);
            throw new ConflictException(ErrorCode.EVENT_IN_USE);
        }
        log.info("[행사 삭제] eventId={}, sources={}", eventId, sources.size());
    }

    private void suppress(EventSource source, Instant now) {
        SourceSnapshot snapshot = codec.read(source.getRaw());
        LocalDate date = snapshot.eventDate() != null ? snapshot.eventDate() : LocalDate.parse(source.getOccurrenceKey().substring(0, 10));
        EventDiscovery discovery = new EventDiscovery(source.getProvider(), source.getExternalId(), source.getOccurrenceKey(),
                source.getRaw(), date, snapshot.venue() == null ? null : snapshot.venue().name(),
                snapshot.venue() == null ? null : snapshot.venue().city());
        discovery.dismiss(now);
        discoveryRepository.save(discovery);
    }
}
