package com.janspace.attendance.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 연속 이동 토글 허용 규칙: 직전에 간 행사가 0~5일 전이고 다른 도시이며 두 공연장 모두 좌표가 있을 때.
 */
public final class ChainPolicy {

    public static final int MAX_DAYS_APART = 5;

    private ChainPolicy() {
    }

    /** 판정에 필요한 행사 요약. city 나 좌표가 없으면 그 값은 null / false. */
    public record Stop(LocalDate date, String city, boolean located) {
    }

    public static boolean eligible(Stop previous, Stop current) {
        if (previous == null || current == null || !previous.located() || !current.located()) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(previous.date(), current.date());
        if (days < 0 || days > MAX_DAYS_APART) {
            return false;
        }
        return previous.city() != null && current.city() != null
                && !previous.city().trim().equalsIgnoreCase(current.city().trim());
    }
}
