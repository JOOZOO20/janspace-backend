package com.stagelog.integration.kopis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * KOPIS XML 응답 매핑 (실키 없음 — 실연동 미확인). 목록 db 가 여럿·하나·없음, 상세의 relates, 시설의 la/lo·mt13s.
 */
class KopisClientTest {

    static final String BASE = "https://www.kopis.or.kr/openApi/restful";
    static final KopisProperties PROPS = new KopisProperties("svc-key", BASE, "스타더스트 클럽");

    RestClient.Builder builder;
    MockRestServiceServer server;
    KopisClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KopisClient(PROPS, builder);
    }

    @Test
    @DisplayName("공연목록: service·stdate·eddate·cpage·rows·shprfnm(+afterdate) 로 GET, db 두 건 매핑")
    void lists_two() {
        server.expect(requestTo(BASE + "/pblprfr?service=svc-key&stdate=20261023&eddate=20261023&cpage=1&rows=100&shprfnm=%EC%8A%A4%ED%83%80%EB%8D%94%EC%8A%A4%ED%8A%B8%20%ED%81%B4%EB%9F%BD&afterdate=20260920"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/list-two.xml"), MediaType.APPLICATION_XML));

        List<KopisPerformance> items = client.list(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23), "스타더스트 클럽", LocalDate.of(2026, 9, 20));

        server.verify();
        assertThat(items).extracting(KopisPerformance::mt20id).containsExactly("PF178134", "PF190001");
        assertThat(items.get(0).prfnm()).isEqualTo("스타더스트 클럽 단독 공연 [서울]");
        assertThat(items.get(0).prfpdfrom()).isEqualTo("2026.10.23");
        assertThat(items.get(0).prfpdto()).isEqualTo("2026.10.25");
        assertThat(items.get(0).poster()).startsWith("http://www.kopis.or.kr/upload/pfmPoster/");
    }

    @Test
    @DisplayName("공연목록: db 가 하나면 목록 1건, <dbs/> 는 빈 목록")
    void lists_one_and_empty() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("shprfnm=%EC%BD%98%EC%84%9C%ED%8A%B8")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/list-one.xml"), MediaType.APPLICATION_XML));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("shprfnm=%EC%97%86%EC%9D%8C")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/list-empty.xml"), MediaType.APPLICATION_XML));

        assertThat(client.list(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23), "콘서트", null)).hasSize(1);
        assertThat(client.list(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 23), "없음", null)).isEmpty();
    }

    @Test
    @DisplayName("공연상세: mt10id·mt13id·prfruntime·dtguidance·pcseguidance·relates[] 매핑, 5xx 는 예외")
    void detail() {
        server.expect(requestTo(BASE + "/pblprfr/PF178134?service=svc-key"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/detail-PF178134.xml"), MediaType.APPLICATION_XML));
        server.expect(requestTo(BASE + "/pblprfr/PF000000?service=svc-key")).andRespond(withServerError());

        KopisPerformanceDetail d = client.detail("PF178134").orElseThrow();

        assertThat(d.mt10id()).isEqualTo("FC001247");
        assertThat(d.mt13id()).isEqualTo("FC001247-01");
        assertThat(d.prfruntime()).isEqualTo("2시간 30분");
        assertThat(d.dtguidance()).isEqualTo("화요일 ~ 금요일(20:00), 토요일(16:00,19:00), 일요일(15:00,18:00)");
        assertThat(d.pcseguidance()).isEqualTo("R석 143,000원, S석 132,000원, A석 121,000원");
        assertThat(d.relateList()).hasSize(2);
        assertThat(d.relateList().get(0).relateurl()).isEqualTo("https://tickets.interpark.com/goods/26012345");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.detail("PF000000"))
                .isInstanceOf(org.springframework.web.client.HttpServerErrorException.class);
    }

    @Test
    @DisplayName("공연시설: la/lo·adres·mt13s[] 매핑. 빈 <relates/> 는 빈 목록")
    void facility_and_empty_relates() {
        server.expect(requestTo(BASE + "/prfplc/FC001247?service=svc-key"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/facility-FC001247.xml"), MediaType.APPLICATION_XML));
        server.expect(requestTo(BASE + "/pblprfr/PF190001?service=svc-key"))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/detail-PF190001.xml"), MediaType.APPLICATION_XML));

        KopisFacility f = client.facility("FC001247").orElseThrow();
        assertThat(f.la()).isEqualTo("37.5199");
        assertThat(f.lo()).isEqualTo("127.1233");
        assertThat(f.adres()).startsWith("서울특별시 송파구");
        assertThat(f.hallList()).extracting(KopisFacility.KopisHall::mt13id).containsExactly("FC001247-01", "FC001247-02");
        assertThat(client.detail("PF190001").orElseThrow().relateList()).isEmpty();
    }

    @Test
    @DisplayName("서비스 키나 아티스트 검색어 중 하나라도 비면 비활성 (설정 누락 시 보강을 건너뛴다)")
    void disabled_without_key_or_artist() {
        assertThat(client.isEnabled()).isTrue();
        assertThat(new KopisClient(new KopisProperties("svc-key", BASE, " "), builder).isEnabled()).isFalse();
        assertThat(new KopisClient(new KopisProperties(null, BASE, "스타더스트 클럽"), builder).isEnabled()).isFalse();
    }
}
