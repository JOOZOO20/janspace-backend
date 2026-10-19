package com.stagelog.event.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventProposal;
import com.stagelog.event.domain.EventProposalRepository;
import com.stagelog.event.domain.EventRepository;
import com.stagelog.event.domain.ProposalStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팬 제안 등록·내 제안 조회·관리자 상태 변경.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalUseCase {

    private final EventProposalRepository proposalRepository;
    private final EventRepository eventRepository;

    @Transactional
    public EventProposal create(Long eventId, Long userId, String text) {
        Event event = eventRepository.findByIdAndDeletedAtIsNull(eventId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EVENT_NOT_FOUND));
        if (text == null || text.isBlank() || text.length() > EventProposal.CONTENT_MAX_LENGTH) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "제안 내용은 1~" + EventProposal.CONTENT_MAX_LENGTH + "자여야 합니다.");
        }
        EventProposal proposal = proposalRepository.save(new EventProposal(event, userId, text.trim()));
        log.info("[팬 제안 등록] proposalId={}, eventId={}, userId={}", proposal.getId(), eventId, userId);
        return proposal;
    }

    @Transactional(readOnly = true)
    public List<EventProposal> mine(Long userId) {
        return proposalRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<EventProposal> all() {
        return proposalRepository.findAllByOrderByCreatedAtDescIdDesc();
    }

    @Transactional
    public void setStatus(Long proposalId, ProposalStatus status) {
        EventProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.SUGGESTION_NOT_FOUND));
        proposal.changeStatus(status);
        log.info("[팬 제안 상태 변경] proposalId={}, status={}", proposalId, status);
    }
}
