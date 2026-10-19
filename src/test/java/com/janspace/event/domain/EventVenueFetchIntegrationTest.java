package com.janspace.event.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.support.EventIntegrationTest;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * 목록·스냅샷 조회는 공연장을 같은 쿼리로 읽는다. 트랜잭션 밖에서 초기화 여부로 N+1 회귀를 잡는다.
 */
class EventVenueFetchIntegrationTest extends EventIntegrationTest {

    @Autowired
    VenueRepository venueRepository;

    @Test
    @DisplayName("findPage·findAllByIdIn 결과의 공연장은 이미 초기화돼 있다 (행사마다 공연장 SELECT 없음)")
    void list_queries_fetch_venue_together() {
        Venue venue = venueRepository.save(Venue.create("KSPO DOME", SupportedLocale.KO, "서울", "KR"));
        Event event = Event.create("잔나비 단독 공연", SupportedLocale.KO, EventType.CONCERT, DATE);
        event.applySource(EventLockableField.VENUE, venue);
        Long id = eventRepository.save(event).getId();

        Event paged = eventRepository.findPage(null, null, PageRequest.of(0, 10)).getContent().get(0);
        Event byId = eventRepository.findAllByIdInAndDeletedAtIsNull(List.of(id)).get(0);

        assertThat(Hibernate.isInitialized(paged.getVenue())).isTrue();
        assertThat(Hibernate.isInitialized(byId.getVenue())).isTrue();
    }
}
