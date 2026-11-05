package com.stagelog.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.ApplySourcesUseCase;
import com.stagelog.event.application.EventCommand;
import com.stagelog.event.application.EventCommand.EventInput;
import com.stagelog.event.application.event.EventScheduleChanged;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.Provider;
import com.stagelog.notification.domain.PushJob;
import com.stagelog.notification.domain.PushJobStatus;
import com.stagelog.notification.domain.PushKind;
import com.stagelog.notification.support.NotificationIntegrationTest;
import com.stagelog.ops.application.AuditActor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 종료 시각이 없을 때의 기록 알림 추정 예약. 공연 시간이 출처·관리자·같은 시즌 회차에서 바뀌면 SCHEDULED 잡이 같은 id 로 재예약된다.
 * 설정 기본값: 공식 공연 시간 +10분, 추정 +20분, 콘서트 기본 150분, 그 밖의 종류 60분.
 */
class RecordEstimateIntegrationTest extends NotificationIntegrationTest {

    static final String TOUR = "2026 아시아 투어";

    @Autowired
    ApplySourcesUseCase applySources;
    @Autowired
    EventCommand eventCommand;

    Event event(String name, EventType type, LocalDate date, String timezone, LocalDateTime startsAtLocal, String season,
                Integer runningMinutes) {
        Event event = Event.create(name, SupportedLocale.KO, type, date);
        event.applySource(EventLockableField.TIMEZONE, timezone);
        event.applySource(EventLockableField.STARTS_AT, startsAtLocal);
        event.applySource(EventLockableField.SEASON, season);
        event.applySource(EventLockableField.RUNNING_MINUTES, runningMinutes);
        return eventRepository.save(event);
    }

    // 기존 값은 그대로 두고 공연 시간만 주는 KOPIS 스냅샷
    void kopisRunningMinutes(Event event, int minutes) {
        applySources.apply(event.getId(), Map.of(Provider.KOPIS, new SourceSnapshot("PF-" + event.getId(),
                event.getEventDate().toString(), event.getName(), null, null, null, null, event.getStartsAtLocal(),
                event.getEventDate(), minutes)));
    }

    PushJob schedule(Event event) {
        publisher.publishEvent(new EventScheduleChanged(event.getId()));
        PushJob job = requireJob(event.getId(), PushKind.RECORD);
        assertThat(job.getStatus()).isEqualTo(PushJobStatus.SCHEDULED);
        assertThat(job(event.getId(), PushKind.RECORD_FALLBACK)).isEmpty();
        return job;
    }

    @Test
    @DisplayName("시즌 회차 없는 콘서트는 150+20분, KOPIS 가 공연 시간 130분을 채우면 같은 잡이 130+10분으로 재예약된다")
    void kopis_running_minutes_reschedules() {
        Event event = event("서울 공연", EventType.CONCERT, DATE, "Asia/Seoul", DATE.atTime(19, 0), null, null);
        PushJob estimated = schedule(event);
        assertThat(estimated.getDueAt()).isEqualTo(Instant.parse("2026-10-23T12:50:00Z"));

        kopisRunningMinutes(event, 130);

        PushJob official = requireJob(event.getId(), PushKind.RECORD);
        assertThat(official.getId()).isEqualTo(estimated.getId());
        assertThat(official.getStatus()).isEqualTo(PushJobStatus.SCHEDULED);
        assertThat(official.getDueAt()).isEqualTo(Instant.parse("2026-10-23T12:20:00Z"));
    }

    @Test
    @DisplayName("관리자가 공연 시간 120분을 넣으면 120+10분, 이어서 종료 시각 22:05 를 넣으면 같은 RECORD 잡이 종료+10분")
    void admin_running_minutes_then_end_time() {
        Event event = event("서울 공연", EventType.CONCERT, DATE, "Asia/Seoul", DATE.atTime(19, 0), null, null);
        PushJob first = schedule(event);

        eventCommand.update(event.getId(), new EventInput(null, null, null, null, null, null, null, null, null, null,
                Optional.of(120)), AuditActor.admin(1L));
        assertThat(requireJob(event.getId(), PushKind.RECORD).getDueAt()).isEqualTo(Instant.parse("2026-10-23T12:10:00Z"));

        eventCommand.update(event.getId(), new EventInput(null, null, null, null, LocalTime.of(22, 5), null, null, null, null,
                null, null), AuditActor.admin(1L));
        PushJob ended = requireJob(event.getId(), PushKind.RECORD);
        assertThat(ended.getId()).isEqualTo(first.getId());
        assertThat(ended.getDueAt()).isEqualTo(Instant.parse("2026-10-23T13:15:00Z"));
        assertThat(jobs.findAllByEventId(event.getId())).hasSize(1);
    }

    @Test
    @DisplayName("싱가포르 회차는 같은 시즌 서울 회차의 140분을 빌려 140+20분. 서울 공연 시간이 130분으로 바뀌면 싱가포르 잡도 재예약된다")
    void borrows_season_sibling_and_follows_its_change() {
        Event seoul = event("서울 공연", EventType.CONCERT, DATE, "Asia/Seoul", DATE.atTime(19, 0), TOUR, 140);
        LocalDate later = DATE.plusDays(7);
        Event singapore = event("싱가포르 공연", EventType.CONCERT, later, "Asia/Singapore", later.atTime(20, 0), TOUR, null);
        PushJob borrowed = schedule(singapore);
        assertThat(borrowed.getDueAt()).isEqualTo(Instant.parse("2026-10-30T14:40:00Z"));

        kopisRunningMinutes(seoul, 130);

        PushJob rescheduled = requireJob(singapore.getId(), PushKind.RECORD);
        assertThat(rescheduled.getId()).isEqualTo(borrowed.getId());
        assertThat(rescheduled.getDueAt()).isEqualTo(Instant.parse("2026-10-30T14:30:00Z"));
        assertThat(requireJob(seoul.getId(), PushKind.RECORD).getDueAt()).isEqualTo(Instant.parse("2026-10-23T12:20:00Z"));
    }

    @Test
    @DisplayName("페스티벌은 같은 시즌 콘서트 회차가 있어도 60+20분")
    void festival_uses_other_default() {
        event("서울 공연", EventType.CONCERT, DATE, "Asia/Seoul", DATE.atTime(19, 0), TOUR, 140);
        Event festival = event("페스티벌", EventType.FESTIVAL, DATE.plusDays(1), "Asia/Seoul", DATE.plusDays(1).atTime(18, 0), TOUR,
                null);
        assertThat(schedule(festival).getDueAt()).isEqualTo(Instant.parse("2026-10-24T10:20:00Z"));
    }
}
