package com.janspace.attendance.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.janspace.attendance.application.event.AttendanceDeleted;
import com.janspace.attendance.domain.Attendance;
import com.janspace.attendance.support.AttendanceIntegrationTest;
import com.janspace.event.domain.Event;
import com.janspace.event.domain.Venue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 기록 API 규칙 A1·A2·A3·A7·A9 와 오류 코드, Event 응답의 starCount·my, 도메인 이벤트 발행.
 */
class AttendanceApiIntegrationTest extends AttendanceIntegrationTest {

    static final long U_NO_DEPARTURE = 1L;
    static final long U1 = 2L;
    static final long U2 = 3L;

    Venue kspo;
    Venue singapore;
    Venue bangkok;
    Event past;
    Event future;

    @BeforeEach
    void fixtures() {
        insertUser(U_NO_DEPARTURE, null);
        insertUser(U1, SEOUL);
        insertUser(U2, SEOUL);
        kspo = venue("KSPO DOME", "서울", "KR", KSPO);
        singapore = venue("Singapore Indoor Stadium", "Singapore", "SG", SINGAPORE);
        bangkok = venue("Impact Arena", "Bangkok", "TH", BANGKOK);
        past = event("지난 공연", TODAY.minusDays(10), kspo);
        future = event("다음 공연", TODAY.plusDays(10), kspo);
    }

    @Test
    @DisplayName("A1 갈래요: 출발지 없으면 409 DEPARTURE_REQUIRED, 있으면 planned + 스냅샷 + 왕복 거리, Event 응답의 my=GOING")
    void a1_going() throws Exception {
        mvc.perform(post("/api/v1/events/{id}/going", future.getId()).with(user(U_NO_DEPARTURE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTURE_REQUIRED"));
        assertThat(attendances.count()).isZero();

        mvc.perform(post("/api/v1/events/{id}/going", future.getId()).with(user(U1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(future.getId()))
                .andExpect(jsonPath("$.data.starCount").value(0))
                .andExpect(jsonPath("$.data.my.status").value("GOING"))
                .andExpect(jsonPath("$.data.my.recordId").doesNotExist());
        Attendance row = row(U1, future.getId());
        assertThat(row.getPlannedAt()).isNotNull();
        assertThat(row.getAttendedAt()).isNull();
        assertThat(row.getDeparture().getName()).isEqualTo("서울 중구");
        assertThat(row.getDistanceKm().doubleValue()).isBetween(27.3, 28.3);

        // 상세: 로그인하면 my, 비로그인은 null. 갈래요는 별이 아니다
        mvc.perform(get("/api/v1/events/{id}", future.getId()).with(user(U1)))
                .andExpect(jsonPath("$.data.my.status").value("GOING"))
                .andExpect(jsonPath("$.data.starCount").value(0));
        mvc.perform(get("/api/v1/events/{id}", future.getId()))
                .andExpect(jsonPath("$.data.my").doesNotExist());
        mvc.perform(get("/api/v1/events/{id}", future.getId()).with(user(U2)))
                .andExpect(jsonPath("$.data.my.status").value("NONE"));

        // 취소: 소프트 삭제 -> NONE. 다시 누르면 같은 행이 되살아난다 (A7)
        mvc.perform(delete("/api/v1/events/{id}/going", future.getId()).with(user(U1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.my.status").value("NONE"));
        assertThat(row(U1, future.getId()).isDeleted()).isTrue();
        mvc.perform(post("/api/v1/events/{id}/going", future.getId()).with(user(U1))).andExpect(status().isOk());
        assertThat(attendances.count()).isEqualTo(1);
        assertThat(row(U1, future.getId()).getId()).isEqualTo(row.getId());
        assertThat(row(U1, future.getId()).isDeleted()).isFalse();
    }

    @Test
    @DisplayName("A2 지난 행사 소급 기록: 감정 0개 흐린 별 -> PATCH 감정으로 밝은 별. Event 응답 starCount·my=RECORDED, 갈래요 취소는 409")
    void a2_retro_then_bright() throws Exception {
        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(past.getId(), "[]", true, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventId").value(past.getId()))
                .andExpect(jsonPath("$.data.isRetro").value(true))
                .andExpect(jsonPath("$.data.emotions.length()").value(0))
                .andExpect(jsonPath("$.data.departure.name").value("서울 중구"))
                .andExpect(jsonPath("$.data.distanceKm").value(27.6))
                .andExpect(jsonPath("$.data.cumulativeKm").value(27.6))
                .andExpect(jsonPath("$.data.photocardId").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("Z")));
        Attendance row = row(U1, past.getId());
        assertThat(row.brightness()).isEqualTo(Attendance.Brightness.DIM);
        assertThat(row.isStar()).isTrue();

        mvc.perform(patch("/api/v1/records/{id}", row.getId()).with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emotions\":[\"HAPPY\",\"OVERWHELMED\"],\"memo\":\"앙코르\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isRetro").value(false))
                .andExpect(jsonPath("$.data.emotions[0]").value("HAPPY"))
                .andExpect(jsonPath("$.data.emotions[1]").value("OVERWHELMED"))
                .andExpect(jsonPath("$.data.memo").value("앙코르"));
        assertThat(row(U1, past.getId()).brightness()).isEqualTo(Attendance.Brightness.BRIGHT);

        mvc.perform(get("/api/v1/events/{id}", past.getId()).with(user(U1)))
                .andExpect(jsonPath("$.data.starCount").value(1))
                .andExpect(jsonPath("$.data.my.status").value("RECORDED"))
                .andExpect(jsonPath("$.data.my.recordId").value(row.getId()));
        mvc.perform(get("/api/v1/events").param("tab", "PAST").with(user(U1)))
                .andExpect(jsonPath("$.data.content[0].starCount").value(1))
                .andExpect(jsonPath("$.data.content[0].my.status").value("RECORDED"));
        mvc.perform(delete("/api/v1/events/{id}/going", past.getId()).with(user(U1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_ALREADY_EXISTS"));
        // 다른 사람 기록은 404
        mvc.perform(get("/api/v1/records/{id}", row.getId()).with(user(U2)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"));
    }

    @Test
    @DisplayName("PATCH /records/{id}: emotions·songId·memo 각각 키 없음 = 유지, null = 지움(감정은 지울 수 없어 400), 값 = 변경")
    void patch_absent_keeps_null_clears_value_changes() throws Exception {
        long s1 = insertSong("주저하는 연인들을 위해");
        long s2 = insertSong("뜨거운 여름밤은 가고 남은 건 볼품없지만");
        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":" + past.getId() + ",\"emotions\":[\"HAPPY\"],\"songId\":" + s1
                                + ",\"memo\":\"첫 메모\",\"isRetro\":false}"))
                .andExpect(status().isOk());
        long id = row(U1, past.getId()).getId();

        // 1. 감정만 보내면 곡·메모는 그대로
        patchRecord(id, "{\"emotions\":[\"SAD\",\"LONGING\"]}");
        assertRecord(id, "[SAD, LONGING]", s1, "첫 메모");
        // 2. 메모만 보내면 감정·곡은 그대로
        patchRecord(id, "{\"memo\":\"둘째 메모\"}");
        assertRecord(id, "[SAD, LONGING]", s1, "둘째 메모");
        // 3. 곡만 바꾸기
        patchRecord(id, "{\"songId\":" + s2 + "}");
        assertRecord(id, "[SAD, LONGING]", s2, "둘째 메모");
        // 4. 빈 본문은 아무것도 바꾸지 않는다
        patchRecord(id, "{}");
        assertRecord(id, "[SAD, LONGING]", s2, "둘째 메모");
        // 5. null 은 지운다: 곡, 메모 각각
        patchRecord(id, "{\"songId\":null}");
        assertRecord(id, "[SAD, LONGING]", null, "둘째 메모");
        patchRecord(id, "{\"memo\":null}");
        assertRecord(id, "[SAD, LONGING]", null, null);
        // 6. 감정 null 은 "비우기" 라 거절되고 아무것도 바뀌지 않는다
        mvc.perform(patch("/api/v1/records/{id}", id).with(user(U1)).contentType(MediaType.APPLICATION_JSON).content("{\"emotions\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EMOTIONS"));
        assertRecord(id, "[SAD, LONGING]", null, null);
    }

    private void patchRecord(long id, String body) throws Exception {
        mvc.perform(patch("/api/v1/records/{id}", id).with(user(U1)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private void assertRecord(long id, String emotions, Long songId, String memo) {
        Attendance row = attendances.findById(id).orElseThrow();
        assertThat(row.emotions().toString()).isEqualTo(emotions);
        assertThat(row.getBestSongId()).isEqualTo(songId);
        assertThat(row.getMemo()).isEqualTo(memo);
    }

    @Test
    @DisplayName("오류 코드: EVENT_NOT_ENDED, INVALID_EMOTIONS(0개·4개·중복·미지 코드·PATCH 로 비우기), RECORD_ALREADY_EXISTS, SONG_NOT_FOUND, PATCH 메모 500자 초과 400, 비로그인 401")
    void error_codes() throws Exception {
        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(future.getId(), "[\"HAPPY\"]", false, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_ENDED"));
        for (String emotions : new String[] {"[]", "[\"HAPPY\",\"SAD\",\"ANGER\",\"LONELY\"]", "[\"HAPPY\",\"HAPPY\"]", "[\"JOY\"]"}) {
            mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                            .content(recordBody(past.getId(), emotions, false, null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_EMOTIONS"));
        }
        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(past.getId(), "[\"HAPPY\"]", false, null, 999999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SONG_NOT_FOUND"));
        assertThat(attendances.count()).isZero();

        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(past.getId(), "[\"HAPPY\"]", false, null, null)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(past.getId(), "[\"SAD\"]", false, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_ALREADY_EXISTS"));
        long id = row(U1, past.getId()).getId();
        mvc.perform(patch("/api/v1/records/{id}", id).with(user(U1)).contentType(MediaType.APPLICATION_JSON).content("{\"emotions\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EMOTIONS"));
        // PATCH 메모도 생성과 같은 500자 제한 (넘으면 DB 오류 500 이 아니라 400)
        mvc.perform(patch("/api/v1/records/{id}", id).with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\":\"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(patch("/api/v1/records/{id}", id).with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\":\"" + "가".repeat(500) + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/records")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/stats")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/events/{id}/going", past.getId())).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A3 can-chain: 4일 전 다른 도시 true, 같은 도시·6일 전 false. 불허인데 chainedFromPrevious=true 면 400 CHAIN_NOT_ALLOWED")
    void a3_can_chain() throws Exception {
        Event sg = event("싱가포르", TODAY.minusDays(9), singapore);
        Event bkk4 = event("방콕 4일 뒤", TODAY.minusDays(5), bangkok);
        Event sg2 = event("싱가포르 2일 뒤", TODAY.minusDays(7), singapore);
        Event bkk6 = event("방콕 6일 뒤", TODAY.minusDays(3), bangkok);

        mvc.perform(get("/api/v1/records/can-chain").param("eventId", String.valueOf(bkk4.getId())).with(user(U1)))
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.previousEventId").doesNotExist());
        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                .content(recordBody(sg.getId(), "[\"HAPPY\"]", false, null, null))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/records/can-chain").param("eventId", String.valueOf(bkk4.getId())).with(user(U1)))
                .andExpect(jsonPath("$.data.eligible").value(true))
                .andExpect(jsonPath("$.data.previousEventId").value(sg.getId()));
        mvc.perform(get("/api/v1/records/can-chain").param("eventId", String.valueOf(sg2.getId())).with(user(U1)))
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.previousEventId").value(sg.getId()));
        mvc.perform(get("/api/v1/records/can-chain").param("eventId", String.valueOf(bkk6.getId())).with(user(U1)))
                .andExpect(jsonPath("$.data.eligible").value(false));
        mvc.perform(get("/api/v1/records/can-chain").param("eventId", "999999").with(user(U1)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(bkk6.getId(), "[\"HAPPY\"]", false, true, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHAIN_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(bkk4.getId(), "[\"HAPPY\"]", false, true, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chainedFromPrevious").value(true));
        // 연속: 싱가포르는 편도만(약 4,672), 방콕은 싱가포르->방콕 + 귀가(약 5,149)
        assertThat(row(U1, sg.getId()).getDistanceKm().doubleValue()).isBetween(4_600.0, 4_750.0);
        assertThat(row(U1, bkk4.getId()).getDistanceKm().doubleValue()).isBetween(5_050.0, 5_250.0);
    }

    @Test
    @DisplayName("A7 기록 삭제 -> AttendanceDeleted, 같은 행사에 다시 기록하면 같은 행 되살림")
    void a7_delete_revive_and_events() throws Exception {
        long song = insertSong("주저하는 연인들을 위해");
        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(past.getId(), "[\"HAPPY\"]", false, null, song)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.songId").value(song));
        Attendance row = row(U1, past.getId());

        mvc.perform(delete("/api/v1/records/{id}", row.getId()).with(user(U1))).andExpect(status().isOk());
        assertThat(applicationEvents.stream(AttendanceDeleted.class).map(AttendanceDeleted::attendanceId)).containsExactly(row.getId());
        assertThat(row(U1, past.getId()).isDeleted()).isTrue();
        mvc.perform(get("/api/v1/records/{id}", row.getId()).with(user(U1))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/records").with(user(U1))).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(delete("/api/v1/records/{id}", row.getId()).with(user(U1))).andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(past.getId(), "[]", true, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(row.getId()))
                .andExpect(jsonPath("$.data.isRetro").value(true))
                .andExpect(jsonPath("$.data.songId").doesNotExist());
        assertThat(attendances.count()).isEqualTo(1);
        assertThat(row(U1, past.getId()).isDeleted()).isFalse();
    }

    @Test
    @DisplayName("A9 별자리: 별 수 = 갔어요 이상·미삭제 행 수. 갈래요와 삭제 기록은 세지 않고, 공개 목록은 기록 있는 행사만 날짜 내림차순")
    void a9_constellations_and_stats() throws Exception {
        Event older = event("더 지난 공연", TODAY.minusDays(20), bangkok);
        mvc.perform(post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                .content(recordBody(past.getId(), "[\"HAPPY\"]", false, null, null))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/records").with(user(U2)).contentType(MediaType.APPLICATION_JSON)
                .content(recordBody(past.getId(), "[]", true, null, null))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/records").with(user(U2)).contentType(MediaType.APPLICATION_JSON)
                .content(recordBody(older.getId(), "[]", true, null, null))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/events/{id}/going", future.getId()).with(user(U1))).andExpect(status().isOk());
        long deleted = row(U2, older.getId()).getId();
        mvc.perform(delete("/api/v1/records/{id}", deleted).with(user(U2))).andExpect(status().isOk());

        mvc.perform(get("/api/v1/events/{id}/constellation", past.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.starCount").value(2))
                .andExpect(jsonPath("$.data.event.id").value(past.getId()))
                .andExpect(jsonPath("$.data.event.name.original").value("지난 공연"))
                .andExpect(jsonPath("$.data.event.date").value(TODAY.minusDays(10).toString()))
                .andExpect(jsonPath("$.data.event.venue.name.original").value("KSPO DOME"))
                .andExpect(jsonPath("$.data.event.venue.location.lat").value(KSPO[0]));
        mvc.perform(get("/api/v1/events/{id}/constellation", future.getId()))
                .andExpect(jsonPath("$.data.starCount").value(0));
        mvc.perform(get("/api/v1/constellations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].event.id").value(past.getId()))
                .andExpect(jsonPath("$.data[0].starCount").value(2));

        mvc.perform(get("/api/v1/me/stats").with(user(U1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalKm").value(27.6))
                .andExpect(jsonPath("$.data.recordCount").value(1))
                .andExpect(jsonPath("$.data.lastRecordAt").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.data.altitude.passedKm").value(10))
                .andExpect(jsonPath("$.data.altitude.nextKm").value(60))
                .andExpect(jsonPath("$.data.altitude.remainingKm").value(32))
                .andExpect(jsonPath("$.data.altitude.nextNear").value(false));
        mvc.perform(get("/api/v1/me/stats").with(user(U_NO_DEPARTURE)))
                .andExpect(jsonPath("$.data.totalKm").value(0))
                .andExpect(jsonPath("$.data.recordCount").value(0))
                .andExpect(jsonPath("$.data.lastRecordAt").doesNotExist())
                .andExpect(jsonPath("$.data.altitude.passedKm").doesNotExist());
        assertThat(row(U1, past.getId()).getDistanceKm()).isEqualByComparingTo(new BigDecimal("27.6"));
    }
    @Test
    @DisplayName("동시 생성: 같은 사용자·행사로 기록·갈래요를 동시에 보내도 500 없이 하나만 200, 나머지는 409 RECORD_ALREADY_EXISTS, 행은 1개")
    void concurrent_create_is_409_not_500() throws Exception {
        for (int round = 0; round < 3; round++) {
            jdbc.update("DELETE FROM attendances WHERE user_id = ?", U1);
            assertThat(concurrently(recordRequest(), recordRequest())).containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendances WHERE user_id = ?", Integer.class, U1)).isEqualTo(1);

            assertThat(concurrently(post("/api/v1/events/{id}/going", future.getId()).with(user(U1)),
                    post("/api/v1/events/{id}/going", future.getId()).with(user(U1)))).contains(200).allMatch(code -> code == 200 || code == 409);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendances WHERE user_id = ? AND event_id = ?", Integer.class, U1,
                    future.getId())).isEqualTo(1);
        }
    }

    private RequestBuilder recordRequest() {
        return post("/api/v1/records").with(user(U1)).contentType(MediaType.APPLICATION_JSON)
                .content(recordBody(past.getId(), "[\"HAPPY\"]", false, null, null));
    }

    private List<Integer> concurrently(RequestBuilder... requests) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(requests.length)) {
            List<Future<Integer>> calls = new ArrayList<>();
            for (RequestBuilder request : requests) {
                Callable<Integer> call = () -> {
                    start.await();
                    return mvc.perform(request).andReturn().getResponse().getStatus();
                };
                calls.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> call : calls) {
                statuses.add(call.get());
            }
            return statuses;
        }
    }
}
