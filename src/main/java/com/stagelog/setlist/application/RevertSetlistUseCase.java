package com.stagelog.setlist.application;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.NotFoundException;
import com.stagelog.setlist.domain.RevisionSource;
import com.stagelog.setlist.domain.SetlistPage;
import com.stagelog.setlist.domain.SetlistRevision;
import com.stagelog.setlist.domain.SetlistRevisionRepository;
import com.stagelog.setlist.domain.SnapshotCodec;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 되돌리기: 지정 리비전의 스냅샷으로 항목을 교체하고 REVERT 리비전을 남긴다. 편집 횟수에 센다. 현재 버전으로의 되돌리기는 아무것도 하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RevertSetlistUseCase {

    private final SetlistWriter writer;
    private final SetlistRevisionRepository revisions;
    private final EditSetlistUseCase editUseCase;

    @Transactional
    public int revert(Long eventId, Long userId, int version) {
        SetlistPage page = writer.pageOf(eventId);
        SetlistRevision target = revisions.findByEventIdAndVersion(eventId, version)
                .orElseThrow(() -> new NotFoundException(ErrorCode.REVISION_NOT_FOUND));
        if (version == page.getVersion()) {
            return version;
        }
        editUseCase.checkQuota(userId);
        List<SetlistEntry> entries = SnapshotCodec.decode(target.getSnapshot()).stream()
                .map(e -> new SetlistEntry(e.songId(), e.position(), e.inSetlistFm())).toList();
        return writer.write(page, entries, RevisionSource.REVERT, userId);
    }
}
