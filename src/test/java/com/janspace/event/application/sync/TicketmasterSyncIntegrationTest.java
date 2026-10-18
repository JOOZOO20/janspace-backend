package com.janspace.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.application.sync.SyncRunner.SyncResult;
import com.janspace.event.domain.DiscoveryStatus;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventDiscovery;
import com.janspace.event.domain.EventTranslationRepository;
import com.janspace.event.domain.Provider;
import com.janspace.event.domain.TranslationField;
import com.janspace.event.domain.TranslationSource;
import com.janspace.event.domain.Venue;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;

/**
 * Ticketmaster: 같은 날짜의 내부 행사가 없으면 발견, 있으면 연결 + 시작 시각·공연장 시간대·좌표·예매 URL·영문 이름 번역.
 */
class TicketmasterSyncIntegrationTest extends SyncIntegrationTest {

    static final String BASE = "https://app.ticketmaster.com/discovery/v2";

    @Autowired
    TicketmasterSync sync;
    @Autowired
    EventTranslationRepository translationRepository;

    @BeforeEach
    void expectProvider() {
        // 아티스트 ID 는 클라이언트가 프로세스 안에서 캐시하므로 0~1회
        providers.expect(ExpectedCount.between(0, 1), requestTo(Matchers.startsWith(BASE + "/attractions.json?keyword=JANNABI")))
                .andRespond(fixture("ticketmaster/attractions.json", MediaType.APPLICATION_JSON));
        providers.expect(requestTo(BASE + "/events.json?attractionId=K8vZ917o7rV&apikey=test-ticketmaster-key&size=100"))
                .andRespond(fixture("ticketmaster/events.json", MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("내부 행사 없음 -> event_discoveries PENDING (날짜·공연장·도시 초안), 행사는 만들지 않는다")
    void unmatched_becomes_discovery() {
        SyncResult result = sync.sync();

        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("발견 1").contains("연결 0");
        assertThat(eventRepository.findAll()).isEmpty();
        EventDiscovery discovery = discoveryRepository.findByProviderAndExternalIdAndOccurrenceKey(
                Provider.TICKETMASTER, "Z698xZC2Z17q7pF", "2026-11-15").orElseThrow();
        assertThat(discovery.getStatus()).isEqualTo(DiscoveryStatus.PENDING);
        assertThat(discovery.getEventDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(discovery.getVenueName()).isEqualTo("The Star Theatre");
        assertThat(discovery.getCity()).isEqualTo("Singapore");
        assertThat(discovery.getRaw()).contains("Asia/Singapore").contains("2026-11-15T20:00");
    }

    @Test
    @DisplayName("같은 날짜 행사 하나 -> TICKETMASTER 출처 연결, startsAtLocal 20:00 + 공연장 시간대 Asia/Singapore (UTC 12:00Z), EN 번역")
    void matched_links_with_schedule_and_timezone() {
        Event event = newEvent("잔나비 싱가포르 공연", LocalDate.of(2026, 11, 15));

        SyncResult result = sync.sync();

        assertThat(result.summary()).contains("연결 1").contains("발견 0");
        assertThat(sourceRepository.findByEventIdAndProvider(event.getId(), Provider.TICKETMASTER)).isPresent()
                .get().satisfies(s -> assertThat(s.getOccurrenceKey()).isEqualTo("2026-11-15"));
        Event linked = reload(event.getId());
        assertThat(linked.getStartsAtLocal()).isEqualTo(LocalDateTime.of(2026, 11, 15, 20, 0));
        assertThat(linked.getTimezone()).isEqualTo("Asia/Singapore");
        assertThat(linked.getStartsAt()).isEqualTo(Instant.parse("2026-11-15T12:00:00Z"));
        Venue venue = venueOf(event.getId());
        assertThat(venue.getName()).isEqualTo("The Star Theatre");
        assertThat(venue.getCountryCode()).isEqualTo("SG");
        assertThat(venue.getTimezone()).isEqualTo("Asia/Singapore");
        assertThat(venue.coordinates().lat()).isEqualTo(1.3062);
        assertThat(linked.getTicketUrl()).startsWith("https://www.ticketmaster.sg/event/");
        assertThat(linked.getPosterKey()).isEqualTo("https://s1.ticketm.net/dam/a/1/jannabi_RETINA_PORTRAIT_3_4.jpg");
        assertThat(linked.getName()).as("NAVER 출처 없이 TICKETMASTER 이름이 원어로 접힌다").isEqualTo("JANNABI Live in Singapore");
        assertThat(translationRepository.findByEventIdAndFieldAndLocale(event.getId(), TranslationField.NAME, SupportedLocale.EN))
                .isPresent().get().satisfies(t -> {
                    assertThat(t.getText()).isEqualTo("JANNABI Live in Singapore");
                    assertThat(t.getSource()).isEqualTo(TranslationSource.PROVIDER);
                });
        assertThat(discoveryRepository.findAll()).isEmpty();
    }
}
