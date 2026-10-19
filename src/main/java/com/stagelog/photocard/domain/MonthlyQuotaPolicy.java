package com.stagelog.photocard.domain;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * 유저당 월 포토카드 제한. 월 경계는 UTC, 미삭제 장 수로 센다. 값은 설정(stagelog.photocard.per-month).
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

    // 남은 장 수. 0 이면 더 만들 수 없다 (설정을 줄여 used 가 limit 을 넘어도 음수가 되지 않는다)
    public static int remaining(long usedThisMonth, int limit) {
        return (int) Math.max(0, limit - usedThisMonth);
    }
}
