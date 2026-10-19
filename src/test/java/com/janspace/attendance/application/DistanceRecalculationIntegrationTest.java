package com.janspace.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.account.application.event.UserWithdrawn;
import com.janspace.attendance.support.AttendanceIntegrationTest;
import com.janspace.event.application.event.EventScheduleChanged;
import com.janspace.event.application.event.EventVenueChanged;
import com.janspace.event.application.event.VenueCoordinatesChanged;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.GeoPoint;
import com.janspace.event.domain.Venue;
import com.janspace.event.domain.VenueLockableField;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 거리 체인 재계산 A4·A5·A6·A8 과 탈퇴 정리. 도메인 이벤트는 직접 발행한다 (리스너는 트랜잭션 밖 발행도 즉시 처리).
 */
class DistanceRecalculationIntegrationTest extends AttendanceIntegrationTest {

    static final long U1 = 11L;
    static final long U2 = 12L;

    @Autowired
    DistanceRecalculation recalculation;
    @Autowired
    TransactionTemplate tx;

    Venue kspo;
    Venue singapore;
    Venue bangkok;

    @BeforeEach
    void fixtures() {
        insertUser(U1, SEOUL);
        insertUser(U2, SEOUL);
        kspo = venue("KSPO DOME", "서울", "KR", KSPO);
        singapore = venue("Singapore Indoor Stadium", "Singapore", "SG", SINGAPORE);
        bangkok = venue("Impact Arena", "Bangkok", "TH", BANGKOK);
    }

    void record(long user, Event event, boolean chained) throws Exception {
        mvc.perform(post("/api/v1/records").with(user(user)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(event.getId(), "[]", true, chained, null)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A4 E1 -> E2(연속) 인 사용자: EventVenueChanged(E1) 로 E1 이 도쿄로 가면 두 거리가 모두 바뀌고, E1 에 없는 사용자는 그대로")
    void a4_event_venue_changed_recomputes_chain() throws Exception {
        Event e1 = event("E1", TODAY.minusDays(9), singapore);
        Event e2 = event("E2", TODAY.minusDays(6), bangkok);
        Event e3 = event("E3", TODAY.minusDays(8), kspo);
        record(U1, e1, false);
        record(U1, e2, true);
        record(U2, e3, false);
        BigDecimal e1Before = row(U1, e1.getId()).getDistanceKm();
        BigDecimal e2Before = row(U1, e2.getId()).getDistanceKm();
        BigDecimal otherBefore = row(U2, e3.getId()).getDistanceKm();
        assertThat(e1Before.doubleValue()).isBetween(4_600.0, 4_750.0);
        assertThat(e2Before.doubleValue()).isBetween(5_050.0, 5_250.0);

        Venue tokyo = venue("Tokyo Dome", "Tokyo", "JP", TOKYO);
        e1.applySource(EventLockableField.VENUE, tokyo);
        eventRepository.save(e1);
        publisher.publishEvent(new EventVenueChanged(e1.getId()));

        // E1: 서울->도쿄 편도(약 1,149), E2: 도쿄->방콕(약 4,599) + 방콕->서울(약 3,722)
        assertThat(row(U1, e1.getId()).getDistanceKm().doubleValue()).isBetween(1_100.0, 1_200.0);
        assertThat(row(U1, e2.getId()).getDistanceKm().doubleValue()).isBetween(8_200.0, 8_450.0);
        assertThat(row(U2, e3.getId()).getDistanceKm()).isEqualByComparingTo(otherBefore);
    }

    @Test
    @DisplayName("EventScheduleChanged: E1 -> E2(연속) 에서 E1 날짜가 E2 뒤로 옮겨지면 순서가 바뀌어 두 기록 모두 왕복으로 다시 계산된다")
    void event_schedule_changed_reorders_chain() throws Exception {
        Event e1 = event("E1", TODAY.minusDays(9), singapore);
        Event e2 = event("E2", TODAY.minusDays(6), bangkok);
        record(U1, e1, false);
        record(U1, e2, true);
        // 서울->싱가포르 편도(약 4,670) / 싱가포르->방콕(약 1,430) + 방콕->서울(약 3,722)
        assertThat(row(U1, e1.getId()).getDistanceKm().doubleValue()).isBetween(4_600.0, 4_750.0);
        assertThat(row(U1, e2.getId()).getDistanceKm().doubleValue()).isBetween(5_050.0, 5_250.0);

        e1.adminUpdate(EventLockableField.EVENT_DATE, TODAY.minusDays(3));
        eventRepository.save(e1);
        publisher.publishEvent(new EventScheduleChanged(e1.getId()));

        // E2 가 체인 첫 기록이 되어 연속 표시와 무관하게 서울<->방콕 왕복, E1 은 서울<->싱가포르 왕복
        assertThat(row(U1, e2.getId()).getDistanceKm().doubleValue()).isBetween(7_350.0, 7_550.0);
        assertThat(row(U1, e1.getId()).getDistanceKm().doubleValue()).isBetween(9_250.0, 9_450.0);
    }

    @Test
    @DisplayName("체인 재판정 - 직전 삭제: X(도쿄, 30일 전) A(싱가포르) B(방콕, 연속) 에서 A 를 지우면 B 의 직전은 24일 전 X 라 연결이 끊겨 왕복, 연속 선언은 보존")
    void chain_rejudged_after_previous_deleted() throws Exception {
        Event x = event("X", TODAY.minusDays(30), venue("Tokyo Dome", "Tokyo", "JP", TOKYO));
        Event a = event("A", TODAY.minusDays(9), singapore);
        Event b = event("B", TODAY.minusDays(6), bangkok);
        record(U1, x, false);
        record(U1, a, false);
        record(U1, b, true);
        // 싱가포르->방콕(약 1,430) + 방콕->서울(약 3,722)
        assertThat(row(U1, b.getId()).getDistanceKm().doubleValue()).isBetween(5_050.0, 5_250.0);

        mvc.perform(delete("/api/v1/records/{id}", row(U1, a.getId()).getId()).with(user(U1))).andExpect(status().isOk());

        // 도쿄->방콕 편도로 잇지 않고 서울<->방콕 왕복
        assertThat(row(U1, b.getId()).getDistanceKm().doubleValue()).isBetween(7_350.0, 7_550.0);
        assertThat(row(U1, b.getId()).isContinuedFromPrevious()).isTrue();
    }

    @Test
    @DisplayName("체인 재판정 - 소급 삽입: A(싱가포르) B(방콕, 연속) 사이에 같은 도시 C(방콕) 를 소급하면 B 는 왕복, C 를 지우면 다시 A 에 이어진다")
    void chain_rejudged_after_backfill_between() throws Exception {
        Event a = event("A", TODAY.minusDays(9), singapore);
        Event b = event("B", TODAY.minusDays(6), bangkok);
        record(U1, a, false);
        record(U1, b, true);

        Event c = event("C", TODAY.minusDays(7), venue("Rajamangala Stadium", "Bangkok", "TH", BANGKOK));
        record(U1, c, false);

        // B 의 직전은 같은 도시 C: 연결 불가라 서울<->방콕 왕복. A 도 귀가 구간을 되찾아 서울<->싱가포르 왕복
        assertThat(row(U1, b.getId()).getDistanceKm().doubleValue()).isBetween(7_350.0, 7_550.0);
        assertThat(row(U1, a.getId()).getDistanceKm().doubleValue()).isBetween(9_250.0, 9_450.0);
        assertThat(row(U1, b.getId()).isContinuedFromPrevious()).isTrue();

        mvc.perform(delete("/api/v1/records/{id}", row(U1, c.getId()).getId()).with(user(U1))).andExpect(status().isOk());

        assertThat(row(U1, a.getId()).getDistanceKm().doubleValue()).isBetween(4_600.0, 4_750.0);
        assertThat(row(U1, b.getId()).getDistanceKm().doubleValue()).isBetween(5_050.0, 5_250.0);
    }

    @Test
    @DisplayName("재계산 잠금: 같은 사용자의 재계산이 진행 중이면 두 번째 재계산은 앞 트랜잭션이 끝날 때까지 기다린다 (lost update 방지)")
    void recalculation_of_same_user_is_serialized() throws Exception {
        record(U1, event("서울", TODAY.minusDays(3), kspo), false);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            recalculation.forUser(U1);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> recalculation.forUser(U1)));
        Thread.sleep(500);
        assertThat(second).isNotDone();

        release.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
    }

    static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("A5 VenueCoordinatesChanged(V) 는 V 의 행사에 행이 있는 사용자만 재계산한다")
    void a5_venue_coordinates_changed() throws Exception {
        Event atKspo = event("서울 공연", TODAY.minusDays(9), kspo);
        Event atBangkok = event("방콕 공연", TODAY.minusDays(6), bangkok);
        record(U1, atKspo, false);
        record(U2, atBangkok, false);
        BigDecimal u2Before = row(U2, atBangkok.getId()).getDistanceKm();
        assertThat(row(U1, atKspo.getId()).getDistanceKm().doubleValue()).isBetween(27.3, 28.3);

        kspo.adminUpdate(VenueLockableField.COORDINATES, new GeoPoint(KUALA_LUMPUR[0], KUALA_LUMPUR[1]));
        venueRepository.save(kspo);
        publisher.publishEvent(new VenueCoordinatesChanged(kspo.getId()));

        assertThat(row(U1, atKspo.getId()).getDistanceKm().doubleValue()).isBetween(9_100.0, 9_400.0);
        assertThat(row(U2, atBangkok.getId()).getDistanceKm()).isEqualByComparingTo(u2Before);
    }

    @Test
    @DisplayName("A6 좌표 없는 공연장은 거리 null, 좌표가 채워지고 이벤트가 오면 계산된다. 통계 totalKm 도 함께")
    void a6_missing_coordinates_then_filled() throws Exception {
        Venue unknown = venue("좌표 없는 공연장", "부산", "KR", null);
        Event e = event("부산 공연", TODAY.minusDays(3), unknown);
        record(U1, e, false);
        assertThat(row(U1, e.getId()).getDistanceKm()).isNull();
        mvc.perform(get("/api/v1/records").with(user(U1)))
                .andExpect(jsonPath("$.data[0].distanceKm").doesNotExist())
                .andExpect(jsonPath("$.data[0].cumulativeKm").value(0));
        mvc.perform(get("/api/v1/me/stats").with(user(U1))).andExpect(jsonPath("$.data.totalKm").value(0));

        unknown.adminUpdate(VenueLockableField.COORDINATES, new GeoPoint(35.1796, 129.0756));
        venueRepository.save(unknown);
        publisher.publishEvent(new VenueCoordinatesChanged(unknown.getId()));

        assertThat(row(U1, e.getId()).getDistanceKm().doubleValue()).isBetween(600.0, 700.0);
        mvc.perform(get("/api/v1/me/stats").with(user(U1))).andExpect(jsonPath("$.data.recordCount").value(1))
                .andExpect(jsonPath("$.data.altitude.passedKm").value(600));
    }

    @Test
    @DisplayName("A8 소급 기록이 앞에 끼어들면 목록은 최신 행사부터, 누적 거리는 행사 순으로 다시 쌓인다")
    void a8_backfill_reorders_cumulative() throws Exception {
        Event recent = event("최근 방콕", TODAY.minusDays(6), bangkok);
        Event old = event("오래된 서울", TODAY.minusDays(30), kspo);
        record(U1, recent, false);
        mvc.perform(get("/api/v1/records").with(user(U1)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].cumulativeKm").value(row(U1, recent.getId()).getDistanceKm().doubleValue()));

        record(U1, old, false);

        double oldKm = row(U1, old.getId()).getDistanceKm().doubleValue();
        double recentKm = row(U1, recent.getId()).getDistanceKm().doubleValue();
        mvc.perform(get("/api/v1/records").with(user(U1)))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].eventId").value(recent.getId()))
                .andExpect(jsonPath("$.data[0].cumulativeKm").value(round1(oldKm + recentKm)))
                .andExpect(jsonPath("$.data[1].eventId").value(old.getId()))
                .andExpect(jsonPath("$.data[1].cumulativeKm").value(oldKm));
        mvc.perform(get("/api/v1/records/{id}", row(U1, old.getId()).getId()).with(user(U1)))
                .andExpect(jsonPath("$.data.cumulativeKm").value(oldKm));
    }

    @Test
    @DisplayName("UserWithdrawn: 그 사용자의 행만 소프트 삭제되고 별자리에서도 빠진다")
    void user_withdrawn_soft_deletes_rows() throws Exception {
        Event e = event("공연", TODAY.minusDays(3), kspo);
        record(U1, e, false);
        record(U2, e, false);
        mvc.perform(get("/api/v1/events/{id}/constellation", e.getId())).andExpect(jsonPath("$.data.starCount").value(2));

        publisher.publishEvent(new UserWithdrawn(U1));

        assertThat(row(U1, e.getId()).isDeleted()).isTrue();
        assertThat(row(U2, e.getId()).isDeleted()).isFalse();
        mvc.perform(get("/api/v1/events/{id}/constellation", e.getId())).andExpect(jsonPath("$.data.starCount").value(1));
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
