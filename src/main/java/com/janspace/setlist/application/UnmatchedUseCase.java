package com.janspace.setlist.application;

import com.janspace.catalog.application.CatalogCommand;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.setlist.domain.UnmatchedName;
import com.janspace.setlist.domain.UnmatchedNameRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 미매칭 곡명 큐: 목록과 "이 곡으로 연결". 연결하면 별칭이 곡에 붙고 그 행사의 병합을 다시 돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnmatchedUseCase {

    private final UnmatchedNameRepository unmatched;
    private final CatalogCommand catalogCommand;
    private final MergeSetlistFmUseCase mergeUseCase;

    public record Unmatched(Long id, Long eventId, String setlistFmName) {
    }

    @Transactional(readOnly = true)
    public List<Unmatched> list() {
        return unmatched.findAllByOrderByIdDesc().stream().map(u -> new Unmatched(u.getId(), u.getEventId(), u.getRawName())).toList();
    }

    @Transactional
    public void link(Long unmatchedId, Long songId) {
        UnmatchedName name = unmatched.findById(unmatchedId).orElseThrow(() -> new NotFoundException(ErrorCode.UNMATCHED_NAME_NOT_FOUND));
        catalogCommand.addAlias(songId, name.getRawName());
        unmatched.delete(name);
        log.info("[미매칭 곡명 연결] unmatchedId={}, eventId={}, songId={}", unmatchedId, name.getEventId(), songId);
        mergeUseCase.merge(name.getEventId());
    }
}
