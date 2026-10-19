package com.stagelog.event.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.event.application.port.KopisPort.Listing;
import com.stagelog.event.domain.EventSourcePolicy.SourceSnapshot;
import com.stagelog.integration.kopis.KopisClient;
import com.stagelog.integration.kopis.KopisProperties;
import com.stagelog.integration.naver.NaverCalendarClient;
import com.stagelog.integration.naver.NaverCalendarProperties;
import com.stagelog.integration.setlistfm.SetlistFmClient;
import com.stagelog.integration.setlistfm.SetlistFmProperties;
import com.stagelog.integration.ticketmaster.TicketmasterClient;
import com.stagelog.integration.ticketmaster.TicketmasterProperties;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 제공자 응답 -> SourceSnapshot 매핑.
 */
class SourceAdapterTest {

    RestClient.Builder builder;
    MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
    }

    @Test
    @DisplayName("네이버: 3일 일정은 날짜마다 스냅샷(키 YYYY-MM-DD, externalId=scheduleId), 종일이라 시각·공연장 없음")
    void naver_splits_multi_day() {
        server.expect(requestTo(Matchers.startsWith("https://calendar.naver.com/")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/naver/schedules.json"), MediaType.APPLICATION_JSON));
        NaverSourceAdapter adapter = new NaverSourceAdapter(new NaverCalendarClient(
                new NaverCalendarProperties("k", "https://calendar.naver.com/ajax/public/GetScheduleList", "https://calendar.naver.com/publicCalendar"), builder));

        List<SourceSnapshot> out = adapter.occurrences(LocalDate.of(2026, 9, 24), LocalDate.of(2027, 1, 22));

        assertThat(out).extracting(SourceSnapshot::occurrenceKey).containsExactly("2026-10-23", "2026-10-24", "2026-10-25", "2026-10-10");
        assertThat(out).extracting(SourceSnapshot::externalId).containsExactly("1000000000123", "1000000000123", "1000000000123", "1000000000456");
        assertThat(out.get(0).name()).isEqualTo("잔나비 콘서트 KSPO DOME 10/23~25");
        assertThat(out.get(0).eventDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(out.get(0).startsAtLocal()).isNull();
        assertThat(out.get(0).venue()).isNull();
    }

    @Test
    @DisplayName("KOPIS: 금요일은 회차 하나(20:00) 스냅샷에 시설 좌표·도시·러닝타임·예매 URL·설명·포스터, 토요일은 회차마다 '#HH:mm' 키")
    void kopis_snapshots() {
        String base = "https://www.kopis.or.kr/openApi/restful";
        server.expect(ExpectedCount.manyTimes(), requestTo(base + "/pblprfr/PF178134?service=k"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/detail-PF178134.xml"), MediaType.APPLICATION_XML));
        server.expect(ExpectedCount.manyTimes(), requestTo(base + "/prfplc/FC001247?service=k"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/facility-FC001247.xml"), MediaType.APPLICATION_XML));
        server.expect(requestTo(Matchers.startsWith(base + "/pblprfr?")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/list-two.xml"), MediaType.APPLICATION_XML));
        KopisSourceAdapter adapter = new KopisSourceAdapter(new KopisClient(new KopisProperties("k", base), builder));

        List<Listing> listings = adapter.search(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23), "잔나비", null);
        assertThat(listings).hasSize(2);
        assertThat(listings.get(0).covers(LocalDate.of(2026, 10, 25))).isTrue();
        assertThat(listings.get(1).covers(LocalDate.of(2026, 10, 25))).isFalse();

        List<SourceSnapshot> friday = adapter.snapshots("PF178134", LocalDate.of(2026, 10, 23));
        assertThat(friday).hasSize(1);
        SourceSnapshot s = friday.get(0);
        assertThat(s.externalId()).isEqualTo("PF178134");
        assertThat(s.occurrenceKey()).isEqualTo("2026-10-23");
        assertThat(s.name()).isEqualTo("잔나비 단독 공연 [서울]");
        assertThat(s.startsAtLocal()).isEqualTo(LocalDateTime.of(2026, 10, 23, 20, 0));
        assertThat(s.runningMinutes()).isEqualTo(150);
        assertThat(s.ticketUrl()).isEqualTo("https://tickets.interpark.com/goods/26012345");
        assertThat(s.posterUrl()).isEqualTo("http://www.kopis.or.kr/upload/pfmPoster/PF_PF178134_260901_101021.gif");
        assertThat(s.description()).contains("출연: 잔나비").contains("관람연령: 만 7세 이상").contains("가격: R석 143,000원");
        assertThat(s.venue().externalId()).isEqualTo("FC001247-01");
        assertThat(s.venue().name()).isEqualTo("올림픽공원 KSPO DOME");
        assertThat(s.venue().city()).isEqualTo("서울특별시");
        assertThat(s.venue().countryCode()).isEqualTo("KR");
        assertThat(s.venue().location().lat()).isEqualTo(37.5199);
        assertThat(s.venue().location().lng()).isEqualTo(127.1233);

        List<SourceSnapshot> saturday = adapter.snapshots("PF178134", LocalDate.of(2026, 10, 24));
        assertThat(saturday).extracting(SourceSnapshot::occurrenceKey).containsExactly("2026-10-24#16:00", "2026-10-24#19:00");
        assertThat(saturday.get(1).startsAtLocal()).isEqualTo(LocalDateTime.of(2026, 10, 24, 19, 0));

        // 월요일: 그 요일 시각이 없으면 날짜 키 하나, 시각 없음
        assertThat(adapter.snapshots("PF178134", LocalDate.of(2026, 10, 26)).get(0).startsAtLocal()).isNull();
        assertThat(KopisSourceAdapter.runningMinutes("1 시간 30 분")).isEqualTo(90);
        assertThat(KopisSourceAdapter.runningMinutes("100분")).isEqualTo(100);
        assertThat(KopisSourceAdapter.runningMinutes("미정")).isNull();
    }

    @Test
    @DisplayName("Ticketmaster: 키=localDate, startsAtLocal=localDate+localTime, 공연장 좌표·국가·시간대, 예매 URL, 포스터는 가장 큰 3:4")
    void ticketmaster_snapshot() {
        String base = "https://app.ticketmaster.com/discovery/v2";
        server.expect(requestTo(Matchers.startsWith(base + "/attractions.json")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/ticketmaster/attractions.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.startsWith(base + "/events.json")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/ticketmaster/events.json"), MediaType.APPLICATION_JSON));
        TicketmasterSourceAdapter adapter = new TicketmasterSourceAdapter(
                new TicketmasterClient(new TicketmasterProperties("k", base, "JANNABI"), builder));

        List<SourceSnapshot> out = adapter.artistEvents();

        assertThat(out).hasSize(1);
        SourceSnapshot s = out.get(0);
        assertThat(s.externalId()).isEqualTo("Z698xZC2Z17q7pF");
        assertThat(s.occurrenceKey()).isEqualTo("2026-11-15");
        assertThat(s.name()).isEqualTo("JANNABI Live in Singapore");
        assertThat(s.startsAtLocal()).isEqualTo(LocalDateTime.of(2026, 11, 15, 20, 0));
        assertThat(s.ticketUrl()).startsWith("https://www.ticketmaster.sg/event/");
        assertThat(s.posterUrl()).isEqualTo("https://s1.ticketm.net/dam/a/1/jannabi_RETINA_PORTRAIT_3_4.jpg");
        assertThat(s.venue().externalId()).isEqualTo("KovZ9177Ov7");
        assertThat(s.venue().name()).isEqualTo("The Star Theatre");
        assertThat(s.venue().city()).isEqualTo("Singapore");
        assertThat(s.venue().countryCode()).isEqualTo("SG");
        assertThat(s.venue().timezone()).isEqualTo("Asia/Singapore");
        assertThat(s.venue().location().lat()).isEqualTo(1.3062);
        assertThat(s.venue().location().lng()).isEqualTo(103.7884);
    }

    @Test
    @DisplayName("setlist.fm: 키=eventDate(dd-MM-yyyy -> ISO), 공연장 이름·도시·국가, 도시 좌표는 coords 가 있을 때만")
    void setlistfm_snapshot() {
        String base = "https://api.setlist.fm/rest/1.0";
        server.expect(requestTo(base + "/artist/mbid-1/setlists?p=1"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/setlistfm/artist-setlists-sync.json"), MediaType.APPLICATION_JSON));
        SetlistFmSourceAdapter adapter = new SetlistFmSourceAdapter(
                new SetlistFmClient(new SetlistFmProperties("k", "mbid-1", base), builder));

        List<SourceSnapshot> out = adapter.page(1);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).externalId()).isEqualTo("63b2f4a1");
        assertThat(out.get(0).occurrenceKey()).isEqualTo("2026-10-23");
        assertThat(out.get(0).eventDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(out.get(0).venue().name()).isEqualTo("KSPO Dome");
        assertThat(out.get(0).venue().city()).isEqualTo("Seoul");
        assertThat(out.get(0).venue().countryCode()).isEqualTo("KR");
        assertThat(out.get(0).venue().location().lat()).isEqualTo(37.5663889);
        assertThat(out.get(0).name()).isNull();
        assertThat(out.get(1).venue().countryCode()).isEqualTo("TH");
    }
}
