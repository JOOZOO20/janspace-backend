package com.stagelog.setlist.infrastructure.scheduling;

import com.stagelog.setlist.application.SyncPlaylistUseCase;
import com.stagelog.setlist.application.YouTubeQuota;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * YouTube Quota 스케줄 트리거 (진입 어댑터). 리셋(태평양 시간 자정) 직후 오늘·내일 일일 행을 만들고 미룬 재생목록 동기화를 우선순위대로 다시 돌린다.
 * 기동 때도 오늘·내일 행을 만든다(배포 당일에 행이 없어 모든 예약이 실패하는 것을 막는다). 일일 행을 요청 경로에서 만들지 않는 이유는 동시 요청끼리의 잠금 경합이다.
 * 여러 인스턴스가 함께 돌아도 행 생성은 INSERT IGNORE, 재시도는 행사별 Redis 잠금과 Quota 예약이 겹침을 막는다.
 */
@Component
@RequiredArgsConstructor
public class PlaylistQuotaScheduler {

    private final YouTubeQuota quota;
    private final SyncPlaylistUseCase syncPlaylist;

    @EventListener(ApplicationReadyEvent.class)
    public void prepareOnStartup() {
        quota.prepareDays();
    }

    @Scheduled(cron = "${jan.youtube.quota.reset-cron}", zone = "America/Los_Angeles")
    public void afterReset() {
        quota.prepareDays();
        syncPlaylist.retryDeferred();
    }
}
