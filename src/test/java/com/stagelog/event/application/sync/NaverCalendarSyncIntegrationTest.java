package com.stagelog.event.application.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.stagelog.event.application.DeleteEventUseCase;
import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.domain.DiscoveryStatus;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventSource;
import com.stagelog.event.domain.EventSourceChange;
import com.stagelog.event.domain.EventStatus;
import com.stagelog.event.domain.Provider;
import com.stagelog.event.domain.SourceChangeField;
import com.stagelog.event.domain.SourceChangeStatus;
import com.stagelog.ops.domain.SyncStatus;
import java.time.LocalDate;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;

/**
 * 네이버 캘린더 동기화: 자동 생성·멱등·사라진 회차(취소 의심)·이동(확인 큐)·제공자 실패(FAILED + 알림, 예외 없음).
 */
class NaverCalendarSyncIntegrationTest extends SyncIntegrationTest {

    @Autowired
    NaverCalendarSync sync;
    @Autowired
    DeleteEventUseCase deleteEventUseCase;

    private void respondWith(String fixture) {
        providers.reset();
        providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith("https://calendar.naver.com/ajax/public/GetScheduleList?ts=")))
                .andRespond(fixture("naver/" + fixture, MediaType.APPLICATION_JSON));
    }

    private List<Event> events() {
        return eventRepository.findAll();
    }

    @Test
    @DisplayName("3일 일정 + 하루 일정 -> 행사 4건(ACTIVE, NAVER 출처·회차 키), 두 번째 실행은 생성 0. sync_runs 에 OK")
    void creates_events_then_idempotent() {
        respondWith("schedules.json");

        SyncResult first = sync.sync(TODAY);

        assertThat(first.ok()).isTrue();
        assertThat(first.summary()).contains("생성 4").contains("일정 2건");
        List<Event> events = events();
        assertThat(events).hasSize(4);
        assertThat(events).allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(EventStatus.ACTIVE));
        assertThat(events).extracting(Event::getEventDate).containsExactlyInAnyOrder(
                LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 10));
        assertThat(events).filteredOn(e -> e.getEventDate().equals(LocalDate.of(2026, 10, 24)))
                .singleElement().extracting(Event::getName).isEqualTo("스타더스트 클럽 콘서트 KSPO DOME 10/23~25");
        List<EventSource> sources = sourceRepository.findAllByProvider(Provider.NAVER);
        assertThat(sources).hasSize(4);
        assertThat(sources).filteredOn(s -> s.getExternalId().equals("1000000000123"))
                .extracting(EventSource::getOccurrenceKey).containsExactlyInAnyOrder("2026-10-23", "2026-10-24", "2026-10-25");
        assertThat(sources).filteredOn(s -> s.getExternalId().equals("1000000000456"))
                .extracting(EventSource::getOccurrenceKey).containsExactly("2026-10-10");
        assertThat(runs()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(SyncStatus.OK);
            assertThat(run.getSource().name()).isEqualTo("NAVER");
            assertThat(run.getSummary()).isEqualTo(first.summary());
        });
        verify(alertPort).send(contains("생성 4"));

        respondWith("schedules.json");
        SyncResult second = sync.sync(TODAY);

        assertThat(second.summary()).contains("생성 0").contains("갱신 4").contains("취소 의심 0");
        assertThat(events()).hasSize(4);
        assertThat(sourceRepository.findAllByProvider(Provider.NAVER)).hasSize(4);
        assertThat(runs()).hasSize(2);
    }

    @Test
    @DisplayName("관리자가 지운 네이버 행사는 다음 동기화가 다시 만들지 않는다: 단일 일정(새 scheduleId 경로)·여러 날 일정(아는 scheduleId 경로) 모두 억제")
    void deleted_event_is_not_recreated() {
        startEventIdsAboveStaleReferences();
        respondWith("schedules.json");
        sync.sync(TODAY);
        Long single = eventOn(LocalDate.of(2026, 10, 10));
        Long oneOfThree = eventOn(LocalDate.of(2026, 10, 24));

        deleteEventUseCase.delete(single);
        deleteEventUseCase.delete(oneOfThree);
        assertThat(discoveryRepository.findByProviderAndExternalIdAndOccurrenceKey(Provider.NAVER, "1000000000456", "2026-10-10"))
                .hasValueSatisfying(d -> assertThat(d.getStatus()).isEqualTo(DiscoveryStatus.DISMISSED));

        respondWith("schedules.json");
        SyncResult again = sync.sync(TODAY);

        assertThat(again.summary()).contains("생성 0").contains("억제 1").contains("발견 0").contains("취소 의심 0");
        assertThat(events()).extracting(Event::getEventDate)
                .containsExactlyInAnyOrder(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 25));
    }

    private Long eventOn(LocalDate date) {
        return events().stream().filter(e -> e.getEventDate().equals(date)).findFirst().orElseThrow().getId();
    }

    @Test
    @DisplayName("픽스처에서 10/25 회차가 빠지면 그 행사만 CANCEL_SUSPECTED, 새 행사 없음")
    void vanished_occurrence_becomes_cancel_suspected() {
        respondWith("schedules.json");
        sync.sync(TODAY);
        respondWith("schedules-day-removed.json");

        SyncResult result = sync.sync(TODAY);

        assertThat(result.summary()).contains("취소 의심 1").contains("생성 0");
        assertThat(events()).hasSize(4);
        assertThat(events()).filteredOn(e -> e.getEventDate().equals(LocalDate.of(2026, 10, 25)))
                .singleElement().extracting(Event::getStatus).isEqualTo(EventStatus.CANCEL_SUSPECTED);
        assertThat(events()).filteredOn(e -> !e.getEventDate().equals(LocalDate.of(2026, 10, 25)))
                .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(EventStatus.ACTIVE));
        assertThat(discoveryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("일정이 하나도 없는 응답(result=false 봉투 등)은 실패로 기록하고 범위 안 행사를 취소 의심으로 바꾸지 않는다")
    void empty_response_with_known_events_fails_without_suspecting() {
        respondWith("schedules.json");
        sync.sync(TODAY);
        respondWith("schedules-error.json");

        SyncResult result = sync.sync(TODAY);

        assertThat(result.failed()).isTrue();
        assertThat(events()).allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(EventStatus.ACTIVE));
        verify(alertPort).send(contains("[동기화 실패] source=NAVER"));
    }

    @Test
    @DisplayName("한 scheduleId 에서 회차 1개가 사라지고 1개가 나타나면 이동: PENDING EVENT_DATE 1건, 취소 의심·발견·새 행사 없음")
    void moved_occurrence_becomes_pending_event_date() {
        respondWith("schedules.json");
        sync.sync(TODAY);
        respondWith("schedules-day-moved.json");

        SyncResult result = sync.sync(TODAY);

        assertThat(result.summary()).contains("이동 제안 1").contains("취소 의심 0").contains("생성 0");
        assertThat(events()).hasSize(4);
        Event spotify = events().stream().filter(e -> e.getEventDate().equals(LocalDate.of(2026, 10, 10))).findFirst().orElseThrow();
        assertThat(spotify.getStatus()).isEqualTo(EventStatus.ACTIVE);
        List<EventSourceChange> pending = changeRepository.findAllByEventIdAndStatus(spotify.getId(), SourceChangeStatus.PENDING);
        assertThat(pending).singleElement().satisfies(change -> {
            assertThat(change.getField()).isEqualTo(SourceChangeField.EVENT_DATE);
            assertThat(change.getCurrentValue()).isEqualTo("2026-10-10");
            assertThat(change.getProposedValue()).isEqualTo("2026-10-11");
            assertThat(change.getProvider()).isEqualTo(Provider.NAVER);
        });
        assertThat(discoveryRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("제공자 5xx -> 예외 없이 FAILED 결과, sync_runs FAILED, 운영자 알림, 행사 변화 없음")
    void provider_failure_is_recorded_not_thrown() {
        providers.expect(ExpectedCount.manyTimes(), requestTo(Matchers.startsWith("https://calendar.naver.com/")))
                .andRespond(withServerError());

        SyncResult result = sync.sync(TODAY);

        assertThat(result.failed()).isTrue();
        assertThat(result.summary()).startsWith("실패: ");
        assertThat(runs()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(SyncStatus.FAILED);
            assertThat(run.getFinishedAt()).isNotNull();
            assertThat(run.getSummary()).startsWith("실패: ");
        });
        verify(alertPort).send(contains("[동기화 실패] source=NAVER"));
        assertThat(events()).isEmpty();
    }
}
