package com.janspace.event.application;

import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventRepository;
import com.janspace.event.domain.EventStatus;
import com.janspace.ops.application.OperatorAlert;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-3 시각 미입력 알림: 3일 안에 열리는 활성 행사 중 ends_at 이 없는 행사마다 운영자 알림 1건. 같은 날 두 번 보내지 않는다 (last_alerted_on).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MissingScheduleAlertUseCase {

    static final int DAYS_AHEAD = 3;

    private final EventRepository eventRepository;
    private final OperatorAlert operatorAlert;

    // 보낸 건수를 돌려준다
    @Transactional
    public int alertMissingSchedule() {
        LocalDate today = LocalDate.now(EventQuery.LIST_ZONE);
        int sent = 0;
        for (Event event : eventRepository.findAllByStatusAndDeletedAtIsNullAndEndsAtIsNullAndEventDateBetween(
                EventStatus.ACTIVE, today, today.plusDays(DAYS_AHEAD))) {
            if (today.equals(event.getLastAlertedOn())) {
                continue;
            }
            long dMinus = ChronoUnit.DAYS.between(today, event.getEventDate());
            operatorAlert.send("[행사 시각 미입력] D-" + dMinus + " eventId=" + event.getId() + " " + event.getName()
                    + " (" + event.getEventDate() + ") 종료 시각이 없어 기록 알림을 예약할 수 없습니다.");
            event.markAlerted(today);
            sent++;
        }
        log.info("[D-3 시각 미입력 알림] today={}, sent={}", today, sent);
        return sent;
    }
}
