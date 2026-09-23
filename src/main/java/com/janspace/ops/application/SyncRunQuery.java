package com.janspace.ops.application;

import com.janspace.ops.domain.SyncRun;
import com.janspace.ops.domain.SyncRunRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 동기화 이력 조회. 관리자 화면용이며 최신순 상위 N 건만 내린다.
 */
@Service
@RequiredArgsConstructor
public class SyncRunQuery {

    private final SyncRunRepository syncRunRepository;

    @Transactional(readOnly = true)
    public List<SyncRun> latest(int size) {
        return syncRunRepository.findAllByOrderByStartedAtDescIdDesc(Limit.of(size));
    }
}
