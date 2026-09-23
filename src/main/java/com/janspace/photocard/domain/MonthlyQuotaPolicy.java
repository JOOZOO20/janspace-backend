package com.janspace.photocard.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * 유저당 월 포토카드 제한. 월 경계는 UTC, 미삭제 장 수로 센다. 값은 설정(janspace.photocard.per-month).
 */
public final class MonthlyQuotaPolicy {

    private MonthlyQuotaPolicy() {
    }

    /** now 가 속한 UTC 월의 [시작, 다음 달 시작). */
    public record Window(Instant from, Instant to) {
    }

    public static Window monthOf(Instant now) {
        YearMonth month = YearMonth.from(now.atOffset(ZoneOffset.UTC));
        return new Window(month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC),
                month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    // 이미 limit 장이면 더 만들 수 없다
    public static boolean allows(long usedThisMonth, int limit) {
        return usedThisMonth < limit;
    }
}
