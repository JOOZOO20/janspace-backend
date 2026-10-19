package com.stagelog.notification.infrastructure;

import com.stagelog.notification.application.DispatchDuePushJobs;
import com.stagelog.notification.application.SchedulePushJobs;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1분 주기 디스패처 트리거 (stagelog.push.dispatch-cron, "-" 면 비활성). 선점이 행 단위라 인스턴스 간 잠금이 필요 없다.
 * 하루 1회 재예약 보정 (stagelog.push.resync-cron, "-" 면 비활성). upsert 라 인스턴스 여럿이 같이 돌아도 결과가 같다.
 */
@Component
@RequiredArgsConstructor
public class DispatchScheduler {

    private final DispatchDuePushJobs dispatchDuePushJobs;
    private final SchedulePushJobs schedulePushJobs;

    @Scheduled(cron = "${stagelog.push.dispatch-cron}")
    public void run() {
        dispatchDuePushJobs.runCycle();
    }

    @Scheduled(cron = "${stagelog.push.resync-cron}", zone = "Asia/Seoul")
    public void resync() {
        schedulePushJobs.resyncUpcoming(LocalDate.now(ZoneId.of("Asia/Seoul")));
    }
}
