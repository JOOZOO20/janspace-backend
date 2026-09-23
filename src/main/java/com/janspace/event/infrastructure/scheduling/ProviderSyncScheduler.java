package com.janspace.event.infrastructure.scheduling;

import com.janspace.event.application.sync.KopisEnrichment;
import com.janspace.event.application.sync.NaverCalendarSync;
import com.janspace.event.application.sync.SetlistFmEventSync;
import com.janspace.event.application.sync.TicketmasterSync;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 행사 출처 동기화 트리거 (janspace.sync.cron.*, Asia/Seoul). 유스케이스가 예외를 밖으로 내지 않으므로 여기서는 호출만 한다.
 * test 프로파일에서는 등록하지 않는다.
 */
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ProviderSyncScheduler {

    private final NaverCalendarSync naverCalendarSync;
    private final KopisEnrichment kopisEnrichment;
    private final TicketmasterSync ticketmasterSync;
    private final SetlistFmEventSync setlistFmEventSync;

    @Scheduled(cron = "${janspace.sync.cron.naver}", zone = "Asia/Seoul")
    public void naver() {
        naverCalendarSync.sync();
    }

    @Scheduled(cron = "${janspace.sync.cron.kopis}", zone = "Asia/Seoul")
    public void kopis() {
        kopisEnrichment.enrich();
    }

    @Scheduled(cron = "${janspace.sync.cron.ticketmaster}", zone = "Asia/Seoul")
    public void ticketmaster() {
        ticketmasterSync.sync();
    }

    @Scheduled(cron = "${janspace.sync.cron.setlistfm}", zone = "Asia/Seoul")
    public void setlistfm() {
        setlistFmEventSync.sync();
    }
}
