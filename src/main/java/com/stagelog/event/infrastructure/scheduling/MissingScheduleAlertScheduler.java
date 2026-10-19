package com.stagelog.event.infrastructure.scheduling;

import com.stagelog.event.application.MissingScheduleAlertUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D-3 시각 미입력 알림 트리거. 매일 09:00 KST. 진입 어댑터이므로 application 만 호출한다.
 */
@Component
@RequiredArgsConstructor
public class MissingScheduleAlertScheduler {

    private final MissingScheduleAlertUseCase useCase;

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Seoul")
    public void run() {
        useCase.alertMissingSchedule();
    }
}
