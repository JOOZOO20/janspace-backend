package com.stagelog.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.domain.DiscoveryStatus;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventDiscovery;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.Venue;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * setlist.fm: 같은 날짜 행사에 공연장·도시 연결, 국가가 다르면 연결하지 않고 발견, 2년 이전은 무시, 범위 밖 페이지(404)에서 멈춘다.
 */
class SetlistFmEventSyncIntegrationTest extends SyncIntegrationTest {

    static final String BASE = "https://api.setlist.fm/rest/1.0/artist/00000000-0000-4000-8000-00000000a001/setlists?p=";

    @Autowired
    SetlistFmEventSync sync;

    @Test
    @DisplayName("10/23 행사 -> SETLISTFM 출처 + 공연장 KSPO Dome/Seoul/KR, 8/30 KR 공연장 행사에는 태국 셋리를 붙이지 않고 발견")
    void matched_and_unmatched() {
        Event seoul = newEvent("스타더스트 클럽 콘서트", LocalDate.of(2026, 10, 23));
        Event korea = newEvent("국내 페스티벌", LocalDate.of(2026, 8, 30));
        Venue hall = venueRepository.save(Venue.create("올림픽홀", SupportedLocale.KO, "서울", "KR"));
        korea.applySource(EventLockableField.VENUE, hall);
        eventRepository.save(korea);
        providers.expect(requestTo(BASE + "1")).andRespond(fixture("setlistfm/artist-setlists-sync.json", MediaType.APPLICATION_JSON));
        providers.expect(requestTo(BASE + "2")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        SyncResult result = sync.sync(LocalDate.of(2026, 10, 30));

        providers.verify();
        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("연결 1").contains("발견 1").contains("셋리 2건");
        assertThat(sourceRepository.findByEventIdAndProvider(seoul.getId(), Provider.SETLISTFM)).isPresent()
                .get().satisfies(s -> {
                    assertThat(s.getExternalId()).isEqualTo("0000a103");
                    assertThat(s.getOccurrenceKey()).isEqualTo("2026-10-23");
                });
        Event linked = reload(seoul.getId());
        Venue venue = venueOf(seoul.getId());
        assertThat(venue.getName()).isEqualTo("KSPO Dome");
        assertThat(venue.getCity()).isEqualTo("Seoul");
        assertThat(venue.getCountryCode()).isEqualTo("KR");
        assertThat(linked.getStartsAtLocal()).isNull();
        assertThat(sourceRepository.findByEventIdAndProvider(korea.getId(), Provider.SETLISTFM)).isEmpty();
        EventDiscovery discovery = discoveryRepository.findByProviderAndExternalIdAndOccurrenceKey(Provider.SETLISTFM, "0000a104", "2026-08-30")
                .orElseThrow();
        assertThat(discovery.getStatus()).isEqualTo(DiscoveryStatus.PENDING);
        assertThat(discovery.getVenueName()).isEqualTo("UOB Live");
        assertThat(discovery.getCity()).isEqualTo("Bangkok");
        assertThat(eventRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("기준일이 셋리보다 2년 넘게 뒤면 전부 건너뛰고 첫 페이지에서 멈춘다")
    void stops_when_page_is_all_old() {
        providers.expect(requestTo(BASE + "1")).andRespond(fixture("setlistfm/artist-setlists-sync.json", MediaType.APPLICATION_JSON));

        SyncResult result = sync.sync(LocalDate.of(2029, 1, 1));

        providers.verify();
        assertThat(result.summary()).contains("셋리 0건").contains("1페이지");
        assertThat(discoveryRepository.findAll()).isEmpty();
    }
}
