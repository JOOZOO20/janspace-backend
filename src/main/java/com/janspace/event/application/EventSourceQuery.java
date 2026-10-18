package com.janspace.event.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventMatchCandidate;
import com.janspace.event.domain.EventMatchCandidateRepository;
import com.janspace.event.domain.EventRepository;
import com.janspace.event.domain.EventSource;
import com.janspace.event.domain.EventSourceChange;
import com.janspace.event.domain.EventSourceChangeRepository;
import com.janspace.event.domain.EventSourceRepository;
import com.janspace.event.domain.SourceChangeStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 화면용 행사 출처 현황: 연결된 출처, 매칭 후보, 대기 중 변경, ends_at 초안.
 */
@Service
@RequiredArgsConstructor
public class EventSourceQuery {

    private final EventRepository eventRepository;
    private final EventSourceRepository sourceRepository;
    private final EventMatchCandidateRepository candidateRepository;
    private final EventSourceChangeRepository changeRepository;

    public record EventSources(List<EventSource> sources, List<EventMatchCandidate> candidates,
                               List<EventSourceChange> pendingChanges, LocalDateTime endsAtDraft) {
    }

    @Transactional(readOnly = true)
    public EventSources of(Long eventId) {
        Event event = eventRepository.findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
        // ends_at 초안 = starts_at_local + running_minutes. 저장하지 않고 보여주기만 한다
        LocalDateTime draft = event.getStartsAtLocal() != null && event.getRunningMinutes() != null
                ? event.getStartsAtLocal().plusMinutes(event.getRunningMinutes()) : null;
        return new EventSources(sourceRepository.findAllByEventId(eventId), candidateRepository.findAllByEventId(eventId),
                changeRepository.findAllByEventIdAndStatus(eventId, SourceChangeStatus.PENDING), draft);
    }
}
