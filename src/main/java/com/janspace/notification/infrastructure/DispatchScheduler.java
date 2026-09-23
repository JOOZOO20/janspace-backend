package com.janspace.notification.infrastructure;

import com.janspace.notification.application.DispatchDuePushJobs;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 1분 주기 디스패처 트리거 (janspace.push.dispatch-cron, "-" 면 비활성). 선점이 행 단위라 인스턴스 간 잠금이 필요 없다.
 */
@Component
@RequiredArgsConstructor
public class DispatchScheduler {

    private final DispatchDuePushJobs dispatchDuePushJobs;

    @Scheduled(cron = "${janspace.push.dispatch-cron}")
    public void run() {
        dispatchDuePushJobs.runCycle();
    }
}
