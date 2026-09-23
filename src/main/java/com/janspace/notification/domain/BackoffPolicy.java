package com.janspace.notification.domain;

import java.time.Duration;
import java.util.List;

/**
 * 재시도 백오프. attempts 번째 시도가 실패한 뒤 기다리는 시간: 1분, 5분, 30분(이후 고정).
 */
public final class BackoffPolicy {

    // 배달당 자동 전송 시도 상한. 수동 재시도는 여기 포함되지 않는다
    public static final int MAX_AUTO_ATTEMPTS = 3;

    private static final List<Duration> DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));

    private BackoffPolicy() {
    }

    public static Duration delayAfter(int attempts) {
        int index = Math.clamp(attempts, 1, DELAYS.size()) - 1;
        return DELAYS.get(index);
    }

    public static boolean canRetry(int attempts) {
        return attempts < MAX_AUTO_ATTEMPTS;
    }
}
