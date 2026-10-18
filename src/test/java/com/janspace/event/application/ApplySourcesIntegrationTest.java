package com.janspace.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.janspace.event.application.ApplySourcesUseCase.Reconciliation;
import com.janspace.event.application.EventCommand.EventInput;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.application.event.EventVenueChanged;
import com.janspace.event.domain.DiscoveryStatus;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventDiscoveryRepository;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.EventSourceChange;
import com.janspace.event.domain.EventSourceChangeRepository;
import com.janspace.event.domain.EventSourceRepository;
import com.janspace.event.domain.EventStatus;
import com.janspace.event.domain.GeoPoint;
import com.janspace.event.domain.Provider;
import com.janspace.event.domain.SourceChangeField;
import com.janspace.event.domain.SourceChangeStatus;
import com.janspace.event.support.EventIntegrationTest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 접기·잠금·확인 큐·날짜 이동.
 */
class ApplySourcesIntegrationTest extends EventIntegrationTest {

    static final LocalDateTime AT_19 = DATE.atTime(19, 0);
    static final LocalDateTime AT_20 = DATE.atTime(20, 0);

    @Autowired
    ApplySourcesUseCase applySources;
    @Autowired
    EventCommand eventCommand;
    @Autowired
    SourceChangeReviewUseCase review;
    @Autowired
    EventSourceChangeRepository changeRepository;
    @Autowired
    EventSourceRepository sourceRepository;
    @Autowired
    EventDiscoveryRepository discoveryRepository;

    Map<Provider, com.janspace.event.domain.EventSourcePolicy.SourceSnapshot> kopis(LocalDateTime startsAt) {
        return Map.of(Provider.KOPIS, snapshot("PF1", DATE.toString(), "잔나비 단독 공연", startsAt, DATE, null));
    }

    Event reload(Long id) {
        return eventRepository.findById(id).orElseThrow();
    }

    List<EventSourceChange> pending(Long eventId) {
        return changeRepository.findAllByEventIdAndStatus(eventId, SourceChangeStatus.PENDING);
    }

    @Test
    @DisplayName("F5 null -> 값 채움은 즉시 적용되고 EventScheduleChanged 1회, 변경 행 없음")
    void f5_null_to_value_applies_immediately() {
        Long id = newEvent("잔나비 단독 공연", DATE).getId();

        applySources.apply(id, kopis(AT_19));

        Event event = reload(id);
        assertThat(event.getStartsAtLocal()).isEqualTo(AT_19);
        assertThat(event.getStartsAt()).isEqualTo(Instant.parse("2026-10-23T10:00:00Z"));
        assertThat(event.isLocked(EventLockableField.STARTS_AT)).isFalse();
        assertThat(count(EventScheduleChanged.class)).isEqualTo(1);
        assertThat(changeRepository.findAll()).isEmpty();
        assertThat(sourceRepository.findByEventIdAndProvider(id, Provider.KOPIS)).isPresent();
        verify(alertPort, never()).send(anyString());
    }

    @Test
    @DisplayName("F6/F7 값 -> 다른 값은 PENDING 1건 + 알림 1회, 이벤트 없음. 같은 값 재실행도 여전히 1건·알림 1회")
    void f6_f7_value_to_value_is_pending_once() {
        Long id = newEventWithStart("잔나비 단독 공연", DATE, AT_19).getId();

        applySources.apply(id, kopis(AT_20));
        applySources.apply(id, kopis(AT_20));

        assertThat(reload(id).getStartsAtLocal()).isEqualTo(AT_19);
        List<EventSourceChange> pending = pending(id);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getField()).isEqualTo(SourceChangeField.STARTS_AT);
        assertThat(pending.get(0).getCurrentValue()).isEqualTo("2026-10-23T19:00");
        assertThat(pending.get(0).getProposedValue()).isEqualTo("2026-10-23T20:00");
        assertThat(changeRepository.count()).isEqualTo(1);
        verify(alertPort, times(1)).send(anyString());
        assertThat(count(EventScheduleChanged.class)).isZero();

        // 다른 값이 오면 같은 행이 갱신되고 알림이 한 번 더 간다
        applySources.apply(id, kopis(DATE.atTime(21, 0)));
        assertThat(changeRepository.count()).isEqualTo(1);
        assertThat(pending(id).get(0).getProposedValue()).isEqualTo("2026-10-23T21:00");
        verify(alertPort, times(2)).send(anyString());
    }

    @Test
    @DisplayName("F8 승인: 값 적용·UTC 재계산·APPLIED·EventScheduleChanged 1회, 재접기해도 새 PENDING 없음")
    void f8_approve_applies() {
        Long id = newEventWithStart("잔나비 단독 공연", DATE, AT_19).getId();
        applySources.apply(id, kopis(AT_20));
        Long changeId = pending(id).get(0).getId();
        applicationEvents.clear();

        review.approve(changeId);

        Event event = reload(id);
        assertThat(event.getStartsAtLocal()).isEqualTo(AT_20);
        assertThat(event.getStartsAt()).isEqualTo(Instant.parse("2026-10-23T11:00:00Z"));
        assertThat(changeRepository.findById(changeId).orElseThrow().getStatus()).isEqualTo(SourceChangeStatus.APPLIED);
        assertThat(count(EventScheduleChanged.class)).isEqualTo(1);

        applySources.apply(id, kopis(AT_20));
        assertThat(pending(id)).isEmpty();
    }

    @Test
    @DisplayName("F9 거절: 같은 값은 억제되고, 값이 바뀌면 다시 제안된다")
    void f9_reject_suppresses_same_value() {
        Long id = newEventWithStart("잔나비 단독 공연", DATE, AT_19).getId();
        applySources.apply(id, kopis(AT_20));
        review.reject(pending(id).get(0).getId());

        applySources.apply(id, kopis(AT_20));
        assertThat(pending(id)).isEmpty();
        assertThat(reload(id).getStartsAtLocal()).isEqualTo(AT_19);

        applySources.apply(id, kopis(DATE.atTime(21, 0)));
        assertThat(pending(id)).hasSize(1);
        assertThat(pending(id).get(0).getProposedValue()).isEqualTo("2026-10-23T21:00");
    }

    @Test
    @DisplayName("F10 관리자 수정은 잠금이며 이후 접기는 제안조차 하지 않는다")
    void f10_admin_update_locks() {
        Long id = newEvent("잔나비 단독 공연", DATE).getId();
        eventCommand.update(id, new EventInput(null, null, null, LocalTime.of(18, 0), null, null, null, null, null, null, null));

        applySources.apply(id, kopis(AT_20));

        Event event = reload(id);
        assertThat(event.getStartsAtLocal()).isEqualTo(DATE.atTime(18, 0));
        assertThat(event.isLocked(EventLockableField.STARTS_AT)).isTrue();
        assertThat(pending(id)).isEmpty();
        verify(alertPort, never()).send(anyString());
    }

    @Test
    @DisplayName("F11 잠금 해제는 즉시 접기: 비민감 필드는 출처 값 적용, null 로 잠갔던 시각은 채워지며 EventScheduleChanged")
    void f11_unlock_refolds_immediately() {
        Event created = newEvent("관리자 이름", DATE);
        created.adminUpdate(EventLockableField.NAME, "관리자 이름");
        created.adminUpdate(EventLockableField.STARTS_AT, null);
        eventRepository.save(created);
        Long id = created.getId();
        applySources.apply(id, Map.of(Provider.NAVER, snapshot("n1", DATE.toString(), "네이버 이름", null, DATE, null),
                Provider.KOPIS, snapshot("PF1", DATE.toString(), "KOPIS 이름", AT_19, DATE, null)));
        assertThat(reload(id).getName()).isEqualTo("관리자 이름");
        assertThat(reload(id).getStartsAtLocal()).isNull();
        applicationEvents.clear();

        eventCommand.unlock(id, EventLockableField.NAME);
        assertThat(reload(id).getName()).isEqualTo("네이버 이름");
        assertThat(count(EventScheduleChanged.class)).isZero();

        eventCommand.unlock(id, EventLockableField.STARTS_AT);
        assertThat(reload(id).getStartsAtLocal()).isEqualTo(AT_19);
        assertThat(count(EventScheduleChanged.class)).isEqualTo(1);
        assertThat(pending(id)).isEmpty();
    }

    @Test
    @DisplayName("F12 한 제공자만 갱신해도 저장된 다른 출처까지 다시 접는다: KOPIS 단독 갱신이 우선순위가 높은 네이버 이름을 덮지 않는다")
    void f12_single_provider_apply_refolds_all_stored_sources() {
        Long id = newEvent("네이버 이름", DATE).getId();
        applySources.apply(id, Map.of(Provider.NAVER, snapshot("n1", DATE.toString(), "네이버 이름", null, DATE, null)));

        applySources.apply(id, kopis(null));

        assertThat(reload(id).getName()).isEqualTo("네이버 이름");
    }

    @Test
    @DisplayName("F12 setlist.fm 단독 갱신은 우선순위가 높은 KOPIS 공연장을 확인 큐에 올리지 않는다")
    void f12_lower_priority_venue_does_not_propose_change() {
        Long id = newEvent("잔나비 단독 공연", DATE).getId();
        applySources.apply(id, Map.of(Provider.KOPIS, snapshot("PF1", DATE.toString(), "잔나비 단독 공연", null, DATE,
                sourceVenue("FC1", "KSPO DOME", new GeoPoint(37.5199, 127.1233)))));
        Long kspo = reload(id).venueId();

        applySources.apply(id, Map.of(Provider.SETLISTFM, snapshot("s1", DATE.toString(), null, null, DATE,
                sourceVenue("v1", "KSPO Dome Seoul", null))));

        assertThat(reload(id).venueId()).isEqualTo(kspo);
        assertThat(pending(id)).isEmpty();
    }

    @Test
    @DisplayName("F13 공연장 값 -> 다른 값은 PENDING(VENUE), 승인하면 EventVenueChanged")
    void f13_venue_change_goes_through_queue() {
        Long id = newEvent("잔나비 단독 공연", DATE).getId();
        applySources.apply(id, Map.of(Provider.KOPIS, snapshot("PF1", DATE.toString(), "잔나비 단독 공연", null, DATE,
                sourceVenue("FC1", "KSPO DOME", new GeoPoint(37.5199, 127.1233)))));
        Long venueA = reload(id).venueId();
        assertThat(venueA).isNotNull();
        assertThat(count(EventVenueChanged.class)).isEqualTo(1);
        applicationEvents.clear();

        applySources.apply(id, Map.of(Provider.KOPIS, snapshot("PF1", DATE.toString(), "잔나비 단독 공연", null, DATE,
                sourceVenue("FC2", "잠실실내체육관", new GeoPoint(37.515, 127.073)))));

        assertThat(reload(id).venueId()).isEqualTo(venueA);
        List<EventSourceChange> pending = pending(id);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getField()).isEqualTo(SourceChangeField.VENUE);
        assertThat(pending.get(0).getCurrentValue()).isEqualTo(String.valueOf(venueA));
        assertThat(count(EventVenueChanged.class)).isZero();

        review.approve(pending.get(0).getId());
        assertThat(reload(id).venueId()).isEqualTo(Long.valueOf(pending.get(0).getProposedValue()));
        assertThat(count(EventVenueChanged.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("F14/F15 날짜 이동: 사라진 1 + 나타난 1 = PENDING(EVENT_DATE) 1건·알림·발견 없음. 승인하면 날짜·벽시계·UTC·회차 키가 함께 옮겨진다")
    void f14_f15_date_move() {
        Long id = newEventWithStart("잔나비 단독 공연", DATE, AT_19).getId();
        applySources.apply(id, Map.of(Provider.NAVER, snapshot("n1", "2026-10-23", "잔나비 단독 공연", null, DATE, null)));
        applicationEvents.clear();

        Reconciliation result = applySources.reconcileOccurrences(Provider.NAVER, "n1",
                Map.of("2026-10-24", snapshot("n1", "2026-10-24", "잔나비 단독 공연", null, DATE.plusDays(1), null)));

        assertThat(result.moveProposed()).isTrue();
        assertThat(discoveryRepository.count()).isZero();
        assertThat(reload(id).getStatus()).isEqualTo(EventStatus.ACTIVE);
        List<EventSourceChange> pending = pending(id);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getField()).isEqualTo(SourceChangeField.EVENT_DATE);
        assertThat(pending.get(0).getCurrentValue()).isEqualTo("2026-10-23");
        assertThat(pending.get(0).getProposedValue()).isEqualTo("2026-10-24");
        verify(alertPort, times(1)).send(anyString());

        // 같은 관측을 다시 해도 PENDING 은 1건
        applySources.reconcileOccurrences(Provider.NAVER, "n1",
                Map.of("2026-10-24", snapshot("n1", "2026-10-24", "잔나비 단독 공연", null, DATE.plusDays(1), null)));
        assertThat(changeRepository.count()).isEqualTo(1);

        review.approve(pending.get(0).getId());

        Event event = reload(id);
        assertThat(event.getEventDate()).isEqualTo(DATE.plusDays(1));
        assertThat(event.getStartsAtLocal()).isEqualTo(DATE.plusDays(1).atTime(19, 0));
        assertThat(event.getStartsAt()).isEqualTo(Instant.parse("2026-10-24T10:00:00Z"));
        assertThat(sourceRepository.findByEventIdAndProvider(id, Provider.NAVER).orElseThrow().getOccurrenceKey()).isEqualTo("2026-10-24");
        assertThat(count(EventScheduleChanged.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("F16 나타난 키가 둘이면 이동이 아니다: 취소 의심 + 발견 2건 + 알림")
    void f16_two_appeared_keys_are_discoveries() {
        Long id = newEvent("잔나비 단독 공연", DATE).getId();
        applySources.apply(id, Map.of(Provider.NAVER, snapshot("n1", "2026-10-23", "잔나비 단독 공연", null, DATE, null)));

        Reconciliation result = applySources.reconcileOccurrences(Provider.NAVER, "n1", Map.of(
                "2026-10-24", snapshot("n1", "2026-10-24", "잔나비 단독 공연", null, DATE.plusDays(1), null),
                "2026-10-25", snapshot("n1", "2026-10-25", "잔나비 단독 공연", null, DATE.plusDays(2), null)));

        assertThat(result.moveProposed()).isFalse();
        assertThat(result.suspectedEventIds()).containsExactly(id);
        assertThat(result.discoveryIds()).hasSize(2);
        assertThat(reload(id).getStatus()).isEqualTo(EventStatus.CANCEL_SUSPECTED);
        assertThat(discoveryRepository.findAll()).allMatch(d -> d.getStatus() == DiscoveryStatus.PENDING);
        assertThat(pending(id)).isEmpty();
        verify(alertPort, times(1)).send(anyString());
    }
}
