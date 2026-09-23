package com.janspace.integration.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 네이버 캘린더 내부 API 요청 형식과 응답 매핑 (비공식 API — 실연동 미확인).
 */
class NaverCalendarClientTest {

    static org.springframework.util.MultiValueMap<String, String> expectedForm() {
        org.springframework.util.LinkedMultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        form.add("bo", "{\"publishedKey\":\"pubkey\",\"startDate\":\"2026-09-24 00:00:00\",\"endDate\":\"2027-01-22 00:00:00\"}");
        form.add("localUserId", "");
        return form;
    }

    static final NaverCalendarProperties PROPS = new NaverCalendarProperties("pubkey", "cal-id",
            "https://calendar.naver.com/ajax/public/GetScheduleList", "https://calendar.naver.com/publicCalendar");

    @Test
    @DisplayName("POST ?ts=, Referer·X-Requested-With 헤더, form bo={json}&localUserId= 로 보내고 schedules[] 를 돌려준다")
    void posts_form_and_parses() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.startsWith("https://calendar.naver.com/ajax/public/GetScheduleList?ts=")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Referer", "https://calendar.naver.com/publicCalendar?publishedKey=pubkey"))
                .andExpect(header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(expectedForm()))
                .andRespond(withSuccess(new ClassPathResource("fixtures/naver/schedules.json"), MediaType.APPLICATION_JSON));

        List<NaverSchedule> schedules = new NaverCalendarClient(PROPS, builder)
                .schedules(LocalDate.of(2026, 9, 24), LocalDate.of(2027, 1, 22));

        server.verify();
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0).scheduleId()).isEqualTo("1000000000123");
        assertThat(schedules.get(0).title()).isEqualTo("잔나비 콘서트 KSPO DOME 10/23~25");
        assertThat(schedules.get(0).startDate()).isEqualTo("2026-10-23 00:00:00");
        assertThat(schedules.get(0).endDate()).isEqualTo("2026-10-25 23:59:59");
        assertThat(schedules.get(0).isAllDay()).isTrue();
        assertThat(schedules.get(1).title()).isEqualTo("Spotify House Seoul");
    }

    @Test
    @DisplayName("schedules 가 없는 응답은 빈 목록")
    void empty_when_no_schedules() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(Matchers.startsWith("https://calendar.naver.com/")))
                .andRespond(withSuccess("{\"result\":true}", MediaType.APPLICATION_JSON));

        assertThat(new NaverCalendarClient(PROPS, builder).schedules(LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 30))).isEmpty();
    }
}
