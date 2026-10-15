package com.janspace.event.application;

import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventType;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;

/**
 * 다른 기능에 공개하는 행사 공연 시간. OFFICIAL = 행사에 저장된 값(KOPIS·관리자), ESTIMATED = 저장된 값이 없어 추정한 값.
 * 종료 시각은 관리자만 정하므로 추정치는 저장하거나 행사 응답에 내리지 않는다.
 */
public record RunningTime(Duration duration, Basis basis) {

    public enum Basis {
        OFFICIAL, ESTIMATED
    }

    // 저장된 값 > (콘서트) 같은 시즌 콘서트 회차 중 날짜가 가장 가까운 회차의 값 > 종류별 기본값. 같은 거리면 앞 날짜
    static RunningTime of(Event event, Collection<Event> seasonSiblings, Duration concertDefault, Duration otherDefault) {
        if (event.getRunningMinutes() != null) {
            return new RunningTime(Duration.ofMinutes(event.getRunningMinutes()), Basis.OFFICIAL);
        }
        if (!borrowsFromSeason(event)) {
            return new RunningTime(otherDefault, Basis.ESTIMATED);
        }
        // 같은 투어는 회차마다 구성이 비슷해 공연 시간도 비슷하다. 페스티벌 등 다른 종류의 짧은 무대는 빌리지 않는다
        Duration estimated = seasonSiblings.stream()
                .filter(e -> e.getType() == EventType.CONCERT && e.getRunningMinutes() != null)
                .min(Comparator.comparingLong((Event e) -> Math.abs(ChronoUnit.DAYS.between(event.getEventDate(), e.getEventDate())))
                        .thenComparing(Event::getEventDate))
                .map(e -> Duration.ofMinutes(e.getRunningMinutes()))
                .orElse(concertDefault);
        return new RunningTime(estimated, Basis.ESTIMATED);
    }

    // 공연 시간이 없는 콘서트는 같은 시즌 회차의 값을 빌린다 (시즌이 없으면 콘서트 기본값)
    static boolean borrowsFromSeason(Event event) {
        return event.getRunningMinutes() == null && event.getType() == EventType.CONCERT;
    }
}
