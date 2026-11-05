package com.stagelog.ops.infrastructure;

import com.stagelog.ops.application.StorageCleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이전 S3 key 삭제 배치 트리거 (jan.storage.cleanup-cron, Asia/Seoul, "-" 면 비활성). 진입 어댑터이므로 application 만 호출한다.
 */
@Component
@RequiredArgsConstructor
public class StorageCleanupScheduler {

    private final StorageCleanup storageCleanup;

    @Scheduled(cron = "${jan.storage.cleanup-cron}", zone = "Asia/Seoul")
    public void run() {
        storageCleanup.deleteDue();
    }
}
