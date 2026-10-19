package com.stagelog.event.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventMatchCandidate;
import com.stagelog.event.domain.EventMatchCandidateRepository;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.Provider;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 매칭 후보 선택: 고른 후보가 출처로 확정되고 같은 제공자의 나머지 후보는 삭제된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateUseCase {

    private final EventMatchCandidateRepository candidateRepository;
    private final EventRepository eventRepository;
    private final ApplySourcesUseCase applySourcesUseCase;
    private final SourceSnapshotCodec codec;

    // 후보 등록 (동기화). 같은 (event, provider, externalId, key) 는 한 번만
    @Transactional
    public boolean register(Long eventId, Provider provider, SourceSnapshot snapshot) {
        boolean exists = candidateRepository.findAllByEventIdAndProvider(eventId, provider).stream()
                .anyMatch(c -> c.getExternalId().equals(snapshot.externalId()) && c.getOccurrenceKey().equals(snapshot.occurrenceKey()));
        if (exists) {
            return false;
        }
        Event event = eventRepository.findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
        candidateRepository.save(new EventMatchCandidate(event, provider, snapshot.externalId(), snapshot.occurrenceKey(),
                codec.write(snapshot)));
        log.info("[매칭 후보 등록] eventId={}, provider={}, externalId={}, key={}", eventId, provider, snapshot.externalId(),
                snapshot.occurrenceKey());
        return true;
    }

    @Transactional
    public void select(Long eventId, Long candidateId) {
        EventMatchCandidate candidate = candidateRepository.findById(candidateId)
                .filter(c -> c.getEvent().getId().equals(eventId))
                .orElseThrow(() -> new NotFoundException(ErrorCode.CANDIDATE_NOT_FOUND));
        SourceSnapshot snapshot = codec.read(candidate.getRaw());
        candidateRepository.deleteAll(candidateRepository.findAllByEventIdAndProvider(eventId, candidate.getProvider()));
        applySourcesUseCase.apply(eventId, Map.of(candidate.getProvider(), snapshot));
        log.info("[매칭 후보 선택] eventId={}, candidateId={}, provider={}", eventId, candidateId, candidate.getProvider());
    }
}
