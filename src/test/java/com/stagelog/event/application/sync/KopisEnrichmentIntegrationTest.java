package com.stagelog.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventMatchCandidate;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.Venue;
import com.stagelog.ops.domain.SyncStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * KOPIS 보강: 후보 하나 -> 연결 + 공연장·좌표·시각·러닝타임·예매 URL·설명·포스터(S3 재저장), 후보 둘 또는 회차 여럿 -> 매칭 후보 행.
 */
class KopisEnrichmentIntegrationTest extends SyncIntegrationTest {

    static final String BASE = "https://www.kopis.or.kr/openApi/restful";
    static final String KEY = "service=test-kopis-key";
    static final byte[] GIF = "GIF89a-poster".getBytes(StandardCharsets.UTF_8);

    @Autowired
    KopisEnrichment enrichment;

    private void expectDetails() {
        providers.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/pblprfr/PF900001?" + KEY))
                .andRespond(fixture("kopis/detail-PF900001.xml", MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/prfplc/FC900001?" + KEY))
                .andRespond(fixture("kopis/facility-FC900001.xml", MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/pblprfr/PF900003?" + KEY))
                .andRespond(fixture("kopis/detail-PF900003.xml", MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/prfplc/FC900002?" + KEY))
                .andRespond(fixture("kopis/facility-FC900002.xml", MediaType.APPLICATION_XML));
    }

    @Test
    @DisplayName("행사 날짜를 포함하는 KOPIS 공연이 하나 -> 출처 연결 + 시설 좌표·시작 시각·러닝타임·예매 URL·설명·S3 포스터 키")
    void exactly_one_candidate_links_and_enriches() {
        Event event = newEvent("스타더스트 클럽 콘서트 KSPO DOME 10/23~25", LocalDate.of(2026, 10, 23));
        providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith(BASE + "/pblprfr?" + KEY + "&stdate=20261023&eddate=20261023")))
                .andRespond(fixture("kopis/list-one.xml", MediaType.APPLICATION_XML));
        expectDetails();
        providers.expect(requestTo("http://www.kopis.or.kr/upload/pfmPoster/PF_PF900001_260901_101021.gif"))
                .andRespond(withSuccess(GIF, MediaType.IMAGE_GIF));

        SyncResult result = enrichment.enrich(TODAY);

        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("연결 1").contains("후보 0").contains("미매칭 0");
        List<EventSource> sources = sourceRepository.findAllByEventId(event.getId());
        assertThat(sources).singleElement().satisfies(s -> {
            assertThat(s.getProvider()).isEqualTo(Provider.KOPIS);
            assertThat(s.getExternalId()).isEqualTo("PF900001");
            assertThat(s.getOccurrenceKey()).isEqualTo("2026-10-23");
        });
        Event enriched = reload(event.getId());
        Venue venue = venueOf(event.getId());
        assertThat(venue.getName()).isEqualTo("올림픽공원 KSPO DOME");
        assertThat(venue.getCity()).isEqualTo("서울특별시");
        assertThat(venue.getCountryCode()).isEqualTo("KR");
        assertThat(venue.coordinates().lat()).isEqualTo(37.5199);
        assertThat(venue.coordinates().lng()).isEqualTo(127.1233);
        assertThat(enriched.getStartsAtLocal()).isEqualTo(LocalDateTime.of(2026, 10, 23, 20, 0));
        assertThat(enriched.getRunningMinutes()).isEqualTo(150);
        assertThat(enriched.getTicketUrl()).isEqualTo("https://tickets.interpark.com/goods/26012345");
        assertThat(enriched.getDescription()).contains("출연: 스타더스트 클럽").contains("가격: R석 143,000원");
        assertThat(enriched.getName()).as("이름은 NAVER > KOPIS 우선순위지만 NAVER 출처가 없어 KOPIS 이름을 따른다").isEqualTo("스타더스트 클럽 단독 공연 [서울]");
        String posterKey = "events/" + event.getId() + "/poster.gif";
        assertThat(enriched.getPosterKey()).isEqualTo(posterKey);
        verify(s3Client).putObject(argThat((PutObjectRequest r) -> r.key().equals(posterKey) && "image/gif".equals(r.contentType())),
                any(RequestBody.class));
        assertThat(runs()).singleElement().extracting(r -> r.getStatus()).isEqualTo(SyncStatus.OK);
        assertThat(candidateRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("후보 둘 -> event_match_candidates 2행·출처 없음, 토요일 회차 둘 -> 시각별 후보 2행")
    void multiple_candidates_are_queued() {
        Event friday = newEvent("스타더스트 클럽 단독 공연", LocalDate.of(2026, 10, 23));
        Event saturday = newEvent("스타더스트 클럽 콘서트 KSPO DOME", LocalDate.of(2026, 10, 24));
        providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString("stdate=20261023&eddate=20261023")))
                .andRespond(fixture("kopis/list-two.xml", MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.containsString("stdate=20261024&eddate=20261024")))
                .andRespond(fixture("kopis/list-one.xml", MediaType.APPLICATION_XML));
        expectDetails();

        SyncResult result = enrichment.enrich(TODAY);

        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("연결 0").contains("후보 4");
        assertThat(sourceRepository.findAll()).isEmpty();
        List<EventMatchCandidate> fridayCandidates = candidateRepository.findAllByEventId(friday.getId());
        assertThat(fridayCandidates).extracting(EventMatchCandidate::getExternalId).containsExactlyInAnyOrder("PF900001", "PF900003");
        assertThat(fridayCandidates).allSatisfy(c -> assertThat(c.getOccurrenceKey()).isEqualTo("2026-10-23"));
        List<EventMatchCandidate> saturdayCandidates = candidateRepository.findAllByEventId(saturday.getId());
        assertThat(saturdayCandidates).extracting(EventMatchCandidate::getOccurrenceKey)
                .containsExactlyInAnyOrder("2026-10-24#16:00", "2026-10-24#19:00");
        assertThat(reload(friday.getId()).venueId()).isNull();

        // 재실행은 같은 후보를 다시 만들지 않는다
        enrichment.enrich(TODAY);
        assertThat(candidateRepository.findAll()).hasSize(4);
    }
}
