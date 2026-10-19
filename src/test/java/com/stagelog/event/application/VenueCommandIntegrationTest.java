package com.stagelog.event.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

import com.stagelog.event.application.VenueCommand.VenueUpdate;
import com.stagelog.event.application.event.EventScheduleChanged;
import com.stagelog.event.application.event.VenueCoordinatesChanged;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.Venue;
import com.stagelog.event.domain.VenueLockableField;
import com.stagelog.event.domain.VenueRepository;
import com.stagelog.event.support.EventIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 공연장 좌표 최초 채움·관리자 수정·시간대 전파.
 */
class VenueCommandIntegrationTest extends EventIntegrationTest {

    @Autowired
    VenueCommand venueCommand;
    @Autowired
    VenueRepository venueRepository;

    @Test
    @DisplayName("F12 출처 좌표로 공연장이 만들어지면 VenueCoordinatesChanged 1회. 다른 제공자의 다른 좌표는 무시(채움 한 번)")
    void f12_coordinates_fill_once() {
        Venue first = venueCommand.resolveFromSource(Provider.KOPIS, sourceVenue("FC1", "KSPO DOME", new GeoPoint(37.5199, 127.1233)));
        assertThat(count(VenueCoordinatesChanged.class)).isEqualTo(1);

        Venue second = venueCommand.resolveFromSource(Provider.TICKETMASTER, sourceVenue("tm-9", "KSPO DOME", new GeoPoint(37.0, 127.0)));

        assertThat(second.getId()).isEqualTo(first.getId());
        Venue venue = venueRepository.findById(first.getId()).orElseThrow();
        assertThat(venue.coordinates()).isEqualTo(new GeoPoint(37.5199, 127.1233));
        assertThat(venue.isLocked(VenueLockableField.COORDINATES)).isFalse();
        assertThat(count(VenueCoordinatesChanged.class)).isEqualTo(1);
        // 두 제공자 모두 같은 공연장에 연결된다
        assertThat(venueCommand.resolveFromSource(Provider.TICKETMASTER, sourceVenue("tm-9", "다른 이름", null)).getId()).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("F18 공연장 시간대 관리자 수정: 잠기지 않은 행사는 재계산 + EventScheduleChanged(시각 없는 행사 포함), TIMEZONE 잠긴 행사는 그대로")
    void f18_timezone_propagates_to_unlocked_events() {
        Venue venue = venueCommand.resolveFromSource(Provider.KOPIS, sourceVenue("FC1", "Impact Arena", null));
        Event unlocked = newEventWithStart("방콕 공연", DATE, DATE.atTime(21, 0));
        unlocked.applySource(EventLockableField.VENUE, venue);
        eventRepository.save(unlocked);
        Event locked = newEventWithStart("잠긴 공연", DATE, DATE.atTime(21, 0));
        locked.applySource(EventLockableField.VENUE, venue);
        locked.adminUpdate(EventLockableField.TIMEZONE, "Asia/Seoul");
        eventRepository.save(locked);
        Event noTime = newEvent("시각 없는 공연", DATE);
        noTime.applySource(EventLockableField.VENUE, venue);
        eventRepository.save(noTime);
        applicationEvents.clear();

        venueCommand.adminUpdate(venue.getId(), new VenueUpdate(null, null, null, "Asia/Bangkok"));

        Event changed = eventRepository.findById(unlocked.getId()).orElseThrow();
        assertThat(changed.getTimezone()).isEqualTo("Asia/Bangkok");
        assertThat(changed.getStartsAt()).isEqualTo(Instant.parse("2026-10-23T14:00:00Z"));
        Event untouched = eventRepository.findById(locked.getId()).orElseThrow();
        assertThat(untouched.getTimezone()).isEqualTo("Asia/Seoul");
        assertThat(untouched.getStartsAt()).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
        // 시각이 없는 행사도 UTC 변화는 없지만 기록 알림의 당일 현지 시각이 시간대를 따르므로 이벤트를 받는다
        assertThat(eventRepository.findById(noTime.getId()).orElseThrow().getTimezone()).isEqualTo("Asia/Bangkok");
        assertThat(applicationEvents.stream(EventScheduleChanged.class).map(EventScheduleChanged::eventId))
                .containsExactlyInAnyOrder(unlocked.getId(), noTime.getId());
        assertThat(venueRepository.findById(venue.getId()).orElseThrow().isLocked(VenueLockableField.TIMEZONE)).isTrue();
    }

    @Test
    @DisplayName("F19 채움 뒤 관리자 좌표 수정: VenueCoordinatesChanged + COORDINATES 잠금, 이후 출처 좌표는 무시")
    void f19_admin_coordinates_update_locks() {
        when(timezonePort.resolve(anyDouble(), anyDouble())).thenReturn(Optional.of(ZoneId.of("Asia/Seoul")));
        Venue venue = venueCommand.resolveFromSource(Provider.KOPIS, sourceVenue("FC1", "KSPO DOME", new GeoPoint(37.5199, 127.1233)));
        assertThat(venueRepository.findById(venue.getId()).orElseThrow().getTimezone()).isEqualTo("Asia/Seoul");
        applicationEvents.clear();

        venueCommand.adminUpdate(venue.getId(), new VenueUpdate(null, null, new GeoPoint(37.52, 127.13), null));

        Venue updated = venueRepository.findById(venue.getId()).orElseThrow();
        assertThat(updated.coordinates()).isEqualTo(new GeoPoint(37.52, 127.13));
        assertThat(updated.isLocked(VenueLockableField.COORDINATES)).isTrue();
        assertThat(count(VenueCoordinatesChanged.class)).isEqualTo(1);

        venueCommand.resolveFromSource(Provider.TICKETMASTER, sourceVenue("tm-1", "KSPO DOME", new GeoPoint(1.0, 1.0)));
        assertThat(venueRepository.findById(venue.getId()).orElseThrow().coordinates()).isEqualTo(new GeoPoint(37.52, 127.13));
        assertThat(count(VenueCoordinatesChanged.class)).isEqualTo(1);
    }
}
