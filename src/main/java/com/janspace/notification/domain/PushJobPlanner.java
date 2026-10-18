package com.janspace.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 기록 알림 예약 시각 계산. 순수 함수. 종료 시각이 있으면 RECORD(종료 + 10분), 없으면 RECORD_FALLBACK(다음 날 현지 10:00).
 */
public final class PushJobPlanner {

    public static final Duration AFTER_END = Duration.ofMinutes(10);
    public static final LocalTime FALLBACK_LOCAL_TIME = LocalTime.of(10, 0);

    public record Plan(PushKind kind, Instant dueAt) {
    }

    private PushJobPlanner() {
    }

    public static Plan plan(Instant endsAt, LocalDate eventDate, String timezone) {
        if (endsAt != null) {
            return new Plan(PushKind.RECORD, endsAt.plus(AFTER_END));
        }
        Instant dueAt = eventDate.plusDays(1).atTime(FALLBACK_LOCAL_TIME).atZone(ZoneId.of(timezone)).toInstant();
        return new Plan(PushKind.RECORD_FALLBACK, dueAt);
    }
}
