package com.janspace.event.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.application.RunningTime.Basis;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.EventType;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunningTimeTest {

    static final LocalDate DATE = LocalDate.of(2026, 10, 23);
    static final Duration CONCERT = Duration.ofMinutes(150);
    static final Duration OTHER = Duration.ofMinutes(60);

    static Event event(EventType type, LocalDate date, Integer runningMinutes) {
        Event event = Event.create("회차", SupportedLocale.KO, type, date);
        event.applySource(EventLockableField.RUNNING_MINUTES, runningMinutes);
        return event;
    }

    @Test
    @DisplayName("저장된 공연 시간은 OFFICIAL, 없으면 같은 시즌 콘서트 중 날짜가 가장 가까운 회차 값(같은 거리면 앞 날짜)을 ESTIMATED 로 빌린다")
    void official_then_nearest_concert_sibling() {
        assertThat(RunningTime.of(event(EventType.CONCERT, DATE, 125), List.of(), CONCERT, OTHER))
                .isEqualTo(new RunningTime(Duration.ofMinutes(125), Basis.OFFICIAL));

        Event far = event(EventType.CONCERT, DATE.minusDays(10), 130);
        Event near = event(EventType.CONCERT, DATE.plusDays(3), 140);
        Event festival = event(EventType.FESTIVAL, DATE.plusDays(1), 40);
        Event noMinutes = event(EventType.CONCERT, DATE, null);
        assertThat(RunningTime.of(event(EventType.CONCERT, DATE, null), List.of(far, near, festival, noMinutes), CONCERT, OTHER))
                .isEqualTo(new RunningTime(Duration.ofMinutes(140), Basis.ESTIMATED));

        Event before = event(EventType.CONCERT, DATE.minusDays(3), 135);
        assertThat(RunningTime.of(event(EventType.CONCERT, DATE, null), List.of(near, before), CONCERT, OTHER).duration())
                .isEqualTo(Duration.ofMinutes(135));
    }

    @Test
    @DisplayName("빌릴 회차가 없는 콘서트는 콘서트 기본값, 콘서트가 아니면 시즌 회차가 있어도 기타 기본값")
    void defaults_by_type() {
        assertThat(RunningTime.of(event(EventType.CONCERT, DATE, null), List.of(), CONCERT, OTHER))
                .isEqualTo(new RunningTime(CONCERT, Basis.ESTIMATED));
        assertThat(RunningTime.of(event(EventType.FESTIVAL, DATE, null), List.of(event(EventType.CONCERT, DATE, 140)), CONCERT, OTHER))
                .isEqualTo(new RunningTime(OTHER, Basis.ESTIMATED));
    }
}
