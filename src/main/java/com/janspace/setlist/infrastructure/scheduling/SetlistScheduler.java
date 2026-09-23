package com.janspace.setlist.infrastructure.scheduling;

import com.janspace.event.application.EventQuery;
import com.janspace.setlist.application.MergeSetlistFmUseCase;
import com.janspace.setlist.application.SeedSetlistUseCase;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 셋리 스케줄 트리거 (진입 어댑터, application 만 호출). 병합은 매일 04:00 KST (setlist.fm 은 보통 공연 다음 날 올라온다), 시드는 05:00 KST.
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
