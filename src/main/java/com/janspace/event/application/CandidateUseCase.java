package com.janspace.event.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.domain.EventMatchCandidate;
import com.janspace.event.domain.EventMatchCandidateRepository;
import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
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
    private final ApplySourcesUseCase applySourcesUseCase;
    private final SourceSnapshotCodec codec;

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
