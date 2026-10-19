package com.stagelog.event.application;

import com.stagelog.event.application.event.EventScheduleChanged;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 같은 시즌 회차의 공연 시간을 빌려 쓰는 행사(RunningTime)에 EventScheduleChanged 를 다시 발행한다.
 * 한 회차의 공연 시간·시즌·종류·날짜가 바뀌거나 회차가 삭제되면 빌려 쓰던 값이 달라질 수 있다.
 */
@Component
@RequiredArgsConstructor
public class RunningTimeBorrowers {

    private final EventRepository eventRepository;
    private final ApplicationEventPublisher publisher;

    public void republish(String season, Long changedEventId) {
        if (season == null) {
            return;
        }
        for (Event event : eventRepository.findAllBySeasonAndDeletedAtIsNull(season)) {
            if (!event.getId().equals(changedEventId) && RunningTime.borrowsFromSeason(event)) {
                publisher.publishEvent(new EventScheduleChanged(event.getId()));
            }
        }
    }
}
