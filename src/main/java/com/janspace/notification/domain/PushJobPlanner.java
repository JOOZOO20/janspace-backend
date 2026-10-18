package com.janspace.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 기록 알림 예약 시각 계산. 순수 함수이며 여유 시간·당일 발송 시각은 생성 시 받는다.
 * 종료 시각 > 시작 시각 + 공연 시간(공식 또는 추정) > 날짜만 순으로 고른다. 날짜만 있으면 RECORD_FALLBACK, 나머지는 RECORD.
 */
public final class PushJobPlanner {

    public static final Duration AFTER_END = Duration.ofMinutes(10);

    public record Plan(PushKind kind, Instant dueAt) {
    }

    private final Duration officialBuffer;
    private final Duration estimatedBuffer;
    private final LocalTime dateOnlyLocalTime;

    public PushJobPlanner(Duration officialBuffer, Duration estimatedBuffer, LocalTime dateOnlyLocalTime) {
        this.officialBuffer = Objects.requireNonNull(officialBuffer);
        this.estimatedBuffer = Objects.requireNonNull(estimatedBuffer);
        this.dateOnlyLocalTime = Objects.requireNonNull(dateOnlyLocalTime);
    }

    // runningTime 은 시작 시각이 있을 때만 쓴다. officialRunningTime = 행사에 저장된 공연 시간(KOPIS·관리자), 아니면 추정치
    public Plan plan(Instant endsAt, Instant startsAt, Duration runningTime, boolean officialRunningTime, LocalDate eventDate,
                     String timezone) {
        // 케이스 A: 관리자가 넣은 종료 시각. 퇴장 직후
        if (endsAt != null) {
            return new Plan(PushKind.RECORD, endsAt.plus(AFTER_END));
        }
        // 케이스 B: 시작 + 공연 시간 + 여유. 공식 공연 시간은 거의 맞춰 끝나 종료 시각과 같은 여유, 추정치는 빗나갈 수 있어 여유를 더 둔다
        if (startsAt != null) {
            Duration buffer = officialRunningTime ? officialBuffer : estimatedBuffer;
            return new Plan(PushKind.RECORD, startsAt.plus(Objects.requireNonNull(runningTime)).plus(buffer));
        }
        // 케이스 C: 날짜만. 공연 시각을 모르므로 행사 당일 공연장 현지 저녁 시각
        Instant dueAt = eventDate.atTime(dateOnlyLocalTime).atZone(ZoneId.of(timezone)).toInstant();
        return new Plan(PushKind.RECORD_FALLBACK, dueAt);
    }
}
