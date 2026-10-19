package com.stagelog.setlist.infrastructure;

import com.stagelog.account.application.event.UserWithdrawn;
import com.stagelog.setlist.application.SyncPlaylistUseCase;
import com.stagelog.setlist.application.event.SetlistChanged;
import com.stagelog.setlist.domain.SetlistRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 도메인 이벤트 수신: UserWithdrawn -> 편집자 ID 제거(발행자 트랜잭션 안), SetlistChanged -> 커밋 뒤 비동기 재생목록 동기화.
 * 재생목록 실패는 셋리 편집을 깨뜨리지 않는다 (sync_runs 에만 남는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetlistEventListeners {

    private final SetlistRevisionRepository revisions;
    private final SyncPlaylistUseCase syncPlaylist;

    @EventListener
    @Transactional
    public void on(UserWithdrawn event) {
        int cleared = revisions.clearEditor(event.userId());
        log.info("[탈퇴 - 셋리 편집자 제거] userId={}, revisions={}", event.userId(), cleared);
    }

    @Async
    @TransactionalEventListener
    public void on(SetlistChanged event) {
        try {
            syncPlaylist.syncIfConnected(event.eventId());
        } catch (RuntimeException e) {
            log.error("[재생목록 동기화 실패] eventId={}", event.eventId(), e);
        }
    }
}
