package com.janspace.setlist.infrastructure;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.attendance.application.event.BestSongChosen;
import com.janspace.setlist.application.AddSongFromRecording;
import com.janspace.setlist.application.SyncPlaylistUseCase;
import com.janspace.setlist.application.event.SetlistChanged;
import com.janspace.setlist.domain.SetlistRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 도메인 이벤트 수신: UserWithdrawn -> 편집자 ID 제거(발행자 트랜잭션 안), SetlistChanged -> 커밋 뒤 비동기 재생목록 동기화,
 * BestSongChosen(attendance) -> 커밋 뒤 셋리 위키에 없는 곡 추가.
 * 재생목록 실패는 셋리 편집을 깨뜨리지 않는다 (sync_runs 에만 남는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SetlistEventListeners {

    private final SetlistRevisionRepository revisions;
    private final SyncPlaylistUseCase syncPlaylist;
    private final AddSongFromRecording addSongFromRecording;

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

    // 기록이 커밋된 뒤 별도 트랜잭션에서 붙인다. 실패해도 기록 자체는 유지된다
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(BestSongChosen event) {
        boolean added = addSongFromRecording.addIfMissing(event.eventId(), event.songId(), event.userId());
        log.info("[기록에서 곡 추가] eventId={}, songId={}, userId={}, added={}", event.eventId(), event.songId(), event.userId(), added);
    }
}
