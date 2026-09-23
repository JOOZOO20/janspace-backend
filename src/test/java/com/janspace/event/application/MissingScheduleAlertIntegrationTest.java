package com.janspace.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.support.EventIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MissingScheduleAlertIntegrationTest extends EventIntegrationTest {

    @Autowired
    MissingScheduleAlertUseCase useCase;

    @Test
    @DisplayName("D-3 안의 활성 행사 중 ends_at 이 없는 것만 하루 한 번 알린다")
    void alerts_once_per_day_for_events_without_end() {
        LocalDate today = LocalDate.now(EventQuery.LIST_ZONE);
        Event missing = newEvent("시각 없음", today.plusDays(2));
        Event complete = newEventWithStart("완성", today.plusDays(1), today.plusDays(1).atTime(19, 0));
        complete.applySource(EventLockableField.ENDS_AT, today.plusDays(1).atTime(21, 0));
        eventRepository.save(complete);
        newEvent("먼 미래", today.plusDays(10));
        newEvent("지난 행사", today.minusDays(1));

        assertThat(useCase.alertMissingSchedule()).isEqualTo(1);
        assertThat(useCase.alertMissingSchedule()).as("같은 날 재실행은 멱등").isZero();

        verify(alertPort, times(1)).send(contains("eventId=" + missing.getId()));
        verify(alertPort, times(1)).send(anyString());
        assertThat(eventRepository.findById(missing.getId()).orElseThrow().getLastAlertedOn()).isEqualTo(today);
    }
}
