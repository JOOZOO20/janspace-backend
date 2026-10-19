package com.stagelog.setlist.infrastructure.scheduling;

import com.stagelog.event.application.EventQuery;
import com.stagelog.setlist.application.MergeSetlistFmUseCase;
import com.stagelog.setlist.application.SeedSetlistUseCase;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 셋리 스케줄 트리거 (진입 어댑터, application 만 호출). 병합은 매일 04:00 KST (setlist.fm 은 보통 공연 다음 날 올라온다), 시드는 05:00 KST.
 * 단일 인스턴스 전제라 분산 잠금이 없다. 인스턴스를 늘리면 같은 잡이 겹쳐 돌아 setlist.fm 호출이 늘지만, 페이지 행 잠금과 결정적 병합·
 * "잠근 뒤 version 확인" 시드 덕분에 결과(리비전·알림)는 중복되지 않는다. 호출량이 문제가 되면 잡 단위 Redis 잠금을 둔다.
 */
@Component
@RequiredArgsConstructor
public class SetlistScheduler {

    private final MergeSetlistFmUseCase mergeUseCase;
    private final SeedSetlistUseCase seedUseCase;

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void merge() {
        mergeUseCase.mergeDue(LocalDate.now(EventQuery.LIST_ZONE));
    }

    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void seed() {
        seedUseCase.seedUpcoming(LocalDate.now(EventQuery.LIST_ZONE));
    }
}
