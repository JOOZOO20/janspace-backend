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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 미매칭 곡명 큐: 목록과 "이 곡으로 연결". 연결하면 별칭이 곡에 붙고 그 행사의 병합을 다시 돌린다.
 * 별칭·행 삭제를 먼저 커밋하고 재병합(setlist.fm 호출)은 그 뒤 따로 돈다. setlist.fm 장애가 별칭까지 되돌리지 않게 하기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnmatchedUseCase {

    private final UnmatchedNameRepository unmatched;
    private final CatalogCommand catalogCommand;
    private final MergeSetlistFmUseCase mergeUseCase;
    private final TransactionTemplate transactionTemplate;

    public record Unmatched(Long id, Long eventId, String setlistFmName) {
    }

    @Transactional(readOnly = true)
    public List<Unmatched> list() {
        return unmatched.findAllByOrderByIdDesc().stream().map(u -> new Unmatched(u.getId(), u.getEventId(), u.getRawName())).toList();
    }

    // 재병합이 실패하면 예외가 관리자에게 가지만 별칭·삭제는 이미 커밋돼 있다. 관리자 수동 병합이나 병합 스케줄이 다시 돌린다
    public void link(Long unmatchedId, Long songId) {
        Long eventId = transactionTemplate.execute(status -> {
            UnmatchedName name = unmatched.findById(unmatchedId).orElseThrow(() -> new NotFoundException(ErrorCode.UNMATCHED_NAME_NOT_FOUND));
            catalogCommand.addAlias(songId, name.getRawName());
            unmatched.delete(name);
            log.info("[미매칭 곡명 연결] unmatchedId={}, eventId={}, songId={}", unmatchedId, name.getEventId(), songId);
            return name.getEventId();
        });
        mergeUseCase.merge(eventId);
    }
}
