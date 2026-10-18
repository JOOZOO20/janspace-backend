package com.janspace.integration.naver;

import java.time.LocalDate;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 네이버 공개 캘린더 내부 API (비공식, 설계).
 * POST GetScheduleList?ts=<now ms>, Referer 는 공개 캘린더 페이지, X-Requested-With, form bo={json}·localUserId=.
 */
@Slf4j
@Component
public class NaverCalendarClient {

    private final NaverCalendarProperties props;
    private final RestClient restClient;

    public NaverCalendarClient(NaverCalendarProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder.build();
    }

    // [from, to] 날짜 범위의 일정. 종료일은 그날 00:00:00 으로 보내며 네이버가 어떻게 해석하는지는 실연동에서 확인한다
    public List<NaverSchedule> schedules(LocalDate from, LocalDate to) throws RestClientException {
        String bo = "{\"publishedKey\":\"" + props.publishedKey() + "\",\"startDate\":\"" + from
                + " 00:00:00\",\"endDate\":\"" + to + " 00:00:00\"}";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("bo", bo);
        form.add("localUserId", "");
        log.info("[네이버 캘린더 조회] from={}, to={}", from, to);
        NaverScheduleListResponse response = restClient.post()
                .uri(props.scheduleListUri() + "?ts={ts}", System.currentTimeMillis())
                .header(HttpHeaders.REFERER, props.refererUri() + "?publishedKey=" + props.publishedKey())
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(NaverScheduleListResponse.class);
        return response == null || response.schedules() == null ? List.of() : response.schedules();
    }
}
