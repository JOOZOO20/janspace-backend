package com.janspace.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;

/**
 * 관리자 출처 흐름 E2E: KOPIS 보강(픽스처)으로 공연장·좌표·시각이 채워지고, 두 번째 실행이 다른 시각·공연장을 제안하면 확인 큐에 쌓인다.
 * 관리자가 승인하면 GET /events/{id} 에 반영되고 기존 기록의 거리가 다시 계산된다.
 */
class AdminSourceFlowIntegrationTest extends E2eIntegrationTest {

    static final String KOPIS = "https://www.kopis.or.kr/openApi/restful";
    static final LocalDate DATE = TODAY.minusDays(1);

    @Test
    @DisplayName("KOPIS 보강 -> 기록 -> 두 번째 실행의 시각·공연장 제안 -> 승인 -> 행사 반영 + 거리 재계산")
    void kopis_enrichment_then_pending_change_approval_recomputes_distance() throws Exception {
        Session fan = loginAndConsent("naver-userinfo-n1.json");
        mvc.perform(patch("/api/v1/me").with(bearer(fan)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departure\":{\"name\":\"서울 중구\",\"countryCode\":\"KR\",\"lat\":37.5665,\"lng\":126.9780}}"))
                .andExpect(status().isOk());
        Session admin = loginAsAdmin("naver-userinfo-n2.json");

        // 1. 관리자가 이름·날짜만으로 행사를 만든다 (공연장·시각은 출처가 채운다)
        long eventId = json(mvc.perform(post("/api/v1/admin/events").with(bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":{\"original\":\"잔나비 단독 공연\"},\"kind\":\"CONCERT\",\"date\":\"" + DATE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.data.venue").doesNotExist())
                .andReturn()).read("$.data.id", Long.class);

        // 2. 첫 KOPIS 실행: 날짜 검색(afterdate 없음) -> 후보 하나 -> 연결 + 공연장·좌표·시작 시각
        providers.expect(ExpectedCount.manyTimes(), requestTo(allOf(containsString(KOPIS + "/pblprfr?"), not(containsString("afterdate")))))
                .andRespond(withSuccess(listing(), MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(containsString(KOPIS + "/pblprfr/PF178134?")))
                .andRespond(withSuccess(detail("20:00", "올림픽공원 KSPO DOME", "FC001247"), MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(containsString(KOPIS + "/prfplc/FC001247?")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/facility-FC001247.xml"), MediaType.APPLICATION_XML));
        mvc.perform(post("/api/v1/admin/sync/kopis").with(bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.summary").value(containsString("연결 1")));
        providers.reset();

        mvc.perform(get("/api/v1/events/{id}", eventId))
                .andExpect(jsonPath("$.data.startTime").value("20:00"))
                .andExpect(jsonPath("$.data.venue.name.original").value("올림픽공원 KSPO DOME"))
                .andExpect(jsonPath("$.data.venue.location.lat").value(37.5199))
                .andExpect(jsonPath("$.data.venue.location.lng").value(127.1233))
                .andExpect(jsonPath("$.data.source.kopisId").value("PF178134"));

        // 3. 팬이 기록한다: 서울 중구 <-> KSPO 왕복 27.6km
        long recordId = json(mvc.perform(post("/api/v1/records").with(bearer(fan)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(eventId, "[\"HAPPY\"]", false, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.distanceKm").value(27.6))
                .andReturn()).read("$.data.id", Long.class);

        // 4. 두 번째 실행(증분, afterdate): 같은 공연이 19:00·난지한강공원으로 바뀌었다 -> 즉시 적용하지 않고 PENDING 2건
        providers.expect(ExpectedCount.manyTimes(), requestTo(allOf(containsString(KOPIS + "/pblprfr?"), containsString("afterdate="))))
                .andRespond(withSuccess(listing(), MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(containsString(KOPIS + "/pblprfr/PF178134?")))
                .andRespond(withSuccess(detail("19:00", "난지한강공원 야외무대", "FC002222"), MediaType.APPLICATION_XML));
        providers.expect(ExpectedCount.manyTimes(), requestTo(containsString(KOPIS + "/prfplc/FC002222?")))
                .andRespond(withSuccess(new ClassPathResource("fixtures/kopis/facility-FC002222.xml"), MediaType.APPLICATION_XML));
        mvc.perform(post("/api/v1/admin/sync/kopis").with(bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.summary").value(containsString("증분 갱신 1")));
        providers.reset();

        mvc.perform(get("/api/v1/events/{id}", eventId))
                .andExpect(jsonPath("$.data.startTime").value("20:00"))
                .andExpect(jsonPath("$.data.venue.name.original").value("올림픽공원 KSPO DOME"));
        DocumentContext pending = json(mvc.perform(get("/api/v1/admin/source-changes").with(bearer(admin)))
                .andExpect(status().isOk()).andReturn());
        List<Map<String, Object>> changes = pending.read("$.data[?(@.eventId == " + eventId + ")]");
        assertThat(changes).hasSize(2);
        Map<String, Object> startsAt = changes.stream().filter(c -> "STARTS_AT".equals(c.get("field"))).findFirst().orElseThrow();
        Map<String, Object> venue = changes.stream().filter(c -> "VENUE".equals(c.get("field"))).findFirst().orElseThrow();
        assertThat(startsAt.get("proposedValue")).isEqualTo(DATE + "T19:00");
        assertThat(startsAt.get("provider")).isEqualTo("KOPIS");

        // 5. 승인 -> 행사에 반영, 기록 거리는 서울 중구 <-> 난지한강공원 왕복(약 18km)으로 재계산
        mvc.perform(post("/api/v1/admin/source-changes/{id}/approve", startsAt.get("id")).with(bearer(admin))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/source-changes/{id}/approve", venue.get("id")).with(bearer(admin))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/source-changes/{id}/approve", venue.get("id")).with(bearer(admin))).andExpect(status().isConflict());

        mvc.perform(get("/api/v1/events/{id}", eventId))
                .andExpect(jsonPath("$.data.startTime").value("19:00"))
                .andExpect(jsonPath("$.data.venue.name.original").value("난지한강공원 야외무대"))
                .andExpect(jsonPath("$.data.venue.location.lng").value(126.8763));
        List<Map<String, Object>> stillPending = json(mvc.perform(get("/api/v1/admin/source-changes").with(bearer(admin))).andReturn())
                .read("$.data[?(@.eventId == " + eventId + ")]");
        assertThat(stillPending).isEmpty();
        DocumentContext records = json(mvc.perform(get("/api/v1/records").with(bearer(fan))).andReturn());
        assertThat(records.read("$.data[0].id", Long.class)).isEqualTo(recordId);
        double recomputed = records.read("$.data[0].distanceKm", Double.class);
        assertThat(recomputed).isNotEqualTo(27.6).isBetween(17.0, 19.0);
        mvc.perform(get("/api/v1/me/stats").with(bearer(fan))).andExpect(jsonPath("$.data.totalKm").value(recomputed));
    }

    // 공연목록: 행사 날짜를 포함하는 공연 하나
    static String listing() {
        String dotted = DATE.toString().replace('-', '.');
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <dbs><db><mt20id>PF178134</mt20id><prfnm>잔나비 단독 공연 [서울]</prfnm><prfpdfrom>%s</prfpdfrom><prfpdto>%s</prfpdto>
                <fcltynm>올림픽공원 KSPO DOME</fcltynm><genrenm>대중음악</genrenm><prfstate>공연예정</prfstate></db></dbs>
                """.formatted(dotted, dotted);
    }

    // 공연상세: 매일 같은 시각 한 회차, 포스터 없음(S3 재저장 생략), 공연장은 시설(mt10id) 좌표를 따른다
    static String detail(String time, String venueName, String facilityId) {
        String dotted = DATE.toString().replace('-', '.');
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <dbs><db><mt20id>PF178134</mt20id><prfnm>잔나비 단독 공연 [서울]</prfnm><prfpdfrom>%s</prfpdfrom><prfpdto>%s</prfpdto>
                <fcltynm>%s</fcltynm><prfcast>잔나비</prfcast><prfruntime>2시간 30분</prfruntime><prfstate>공연예정</prfstate>
                <mt10id>%s</mt10id><mt13id>%s-01</mt13id><dtguidance>월요일 ~ 일요일(%s)</dtguidance>
                <relates><relate><relatenm>인터파크</relatenm><relateurl>https://tickets.interpark.com/goods/26012345</relateurl></relate></relates>
                </db></dbs>
                """.formatted(dotted, dotted, venueName, facilityId, facilityId, time);
    }
}
