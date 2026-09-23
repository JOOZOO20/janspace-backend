package com.janspace.attendance.application;

import com.janspace.event.application.EventSnapshot;
import java.time.Instant;
import java.util.Comparator;

/**
 * 체인·누적 거리의 기준이 되는 행사 순서: 행사 날짜 -> 시작 순간(없으면 앞) -> 행사 ID.
 */
final class EventOrder {

    static final Comparator<EventSnapshot> COMPARATOR = Comparator
            .comparing(EventSnapshot::eventDate)
            .thenComparing(EventSnapshot::startsAt, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
            .thenComparing(EventSnapshot::id);

    private EventOrder() {
    }
}
