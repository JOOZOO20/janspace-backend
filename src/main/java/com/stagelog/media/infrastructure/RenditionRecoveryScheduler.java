package com.stagelog.media.infrastructure;

import com.stagelog.media.application.RenditionRecovery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Zombie rendition 회수 트리거 (1분마다). 변환이 켜진 프로세스에서만 등록한다. 진입 어댑터이므로 application 만 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "jan.media.transcode.enabled", havingValue = "true")
public class RenditionRecoveryScheduler {

    private final RenditionRecovery recovery;

    @Scheduled(fixedDelayString = "${jan.media.recovery.interval:60s}", initialDelayString = "${jan.media.recovery.interval:60s}")
    public void run() {
        try {
            recovery.recover();
        } catch (RuntimeException e) {
            // DB 장애 등. 다음 주기에 다시 한다
            log.warn("[미디어 Zombie 회수 실패]", e);
        }
    }
}
