package com.stagelog.ticket.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.account.application.event.UserWithdrawn;
import com.stagelog.attendance.support.AttendanceIntegrationTest;
import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.locale.SupportedLocale;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.EventLockableField;
import com.stagelog.event.domain.EventType;
import com.stagelog.event.domain.Venue;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 티켓북: 꽂기·중복 409·남의 티켓 404·부분 수정(키 없음/null)·연번·기록 연결, 인식(글자 날짜 -> 행사 후보, 대체 후보, 입력 상한),
 * 스킨 기간 필터·기본 스킨 규칙·삭제 시 티켓 skinId 정리, 탈퇴 정리, 행사 FK RESTRICT 를 실제 MySQL 로 검증한다.
 */
class TicketIntegrationTest extends AttendanceIntegrationTest {

    static final long U1 = 2L;
    static final long U2 = 3L;
    static final long ADMIN = 99L;
    static final LocalDate DAY1 = LocalDate.of(2026, 9, 16);
    static final LocalDate TODAY_UTC = LocalDate.now(ZoneOffset.UTC);

    Venue kspo;
    Event seoul1;
    Event festival;
    Event seoul2;
    Event seoul3;
    Event upcoming;

    @BeforeEach
    void fixtures() {
        cleanTicketTables();
        insertUser(U1, SEOUL);
        insertUser(U2, SEOUL);
        insertUser(ADMIN, null);
        kspo = venue("KSPO DOME", "서울", "KR", KSPO);
        jdbc.update("INSERT INTO venue_translations (venue_id, field, locale, text, source, reviewed, created_at, updated_at) "
                + "VALUES (?, 'NAME', 'ja', 'KSPOドーム', 'ADMIN', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", kspo.getId());
        // 사흘 연속 공연 + 첫날 같은 날의 페스티벌
        seoul1 = show("스타더스트 클럽 전국투어 - 서울", DAY1, 19, kspo);
        festival = show("그랜드 민트 페스티벌 2026", DAY1, 15, venue("올림픽공원 88잔디마당", "서울", "KR", KSPO));
        seoul2 = show("스타더스트 클럽 전국투어 - 서울", DAY1.plusDays(1), 19, kspo);
        seoul3 = show("스타더스트 클럽 전국투어 - 서울", DAY1.plusDays(2), 19, kspo);
        upcoming = show("스타더스트 클럽 전국투어 - 부산", TODAY.plusDays(20), 18, venue("벡스코", "부산", "KR", KSPO));
    }

    @AfterEach
    void cleanTicketTables() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.execute("TRUNCATE TABLE tickets");
        jdbc.execute("TRUNCATE TABLE ticket_skins");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }

    @Test
    @DisplayName("꽂기·조회: 행사 값(이름·번역·시각·시간대) + 좌석·색(소문자) + 같은 행사의 기록 ID, 중복 409, 없는 행사·스킨 404, 입력 검증 400")
    void create_and_list() throws Exception {
        long rid = record(U1, seoul1);

        DocumentContext created = json(perform(post("/api/v1/tickets"), U1,
                "{\"eventId\":" + seoul1.getId() + ",\"seat\":\" 1층 A구역 12열 5번 \",\"accent\":\"#D94F3D\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK")).andReturn());
        assertThat(created.read("$.data.serial", Integer.class)).isEqualTo(1);
        assertThat(created.read("$.data.seat", String.class)).isEqualTo("1층 A구역 12열 5번");
        assertThat(created.read("$.data.accent", String.class)).isEqualTo("#d94f3d");
        assertThat(created.read("$.data.recordId", Long.class)).isEqualTo(rid);
        assertThat(created.read("$.data.skinId", Long.class)).isNull();
        assertThat(created.read("$.data.createdAt", String.class)).endsWith("Z");
        assertThat(created.read("$.data.event.id", Long.class)).isEqualTo(seoul1.getId());
        assertThat(created.read("$.data.event.name.original", String.class)).isEqualTo("스타더스트 클럽 전국투어 - 서울");
        assertThat(created.read("$.data.event.date", String.class)).isEqualTo("2026-09-16");
        assertThat(created.read("$.data.event.startTime", String.class)).isEqualTo("19:00");
        assertThat(created.read("$.data.event.timezone", String.class)).isEqualTo("Asia/Seoul");
        assertThat(created.read("$.data.event.venue.name.original", String.class)).isEqualTo("KSPO DOME");
        assertThat(created.read("$.data.event.venue.name.ja", String.class)).isEqualTo("KSPOドーム");
        assertThat(created.read("$.data.event.venue.city.original", String.class)).isEqualTo("서울");

        // 같은 행사 두 번째는 409. 없는 행사·스킨은 404
        perform(post("/api/v1/tickets"), U1, "{\"eventId\":" + seoul1.getId() + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_ALREADY_EXISTS"));
        perform(post("/api/v1/tickets"), U1, "{\"eventId\":999999}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        perform(post("/api/v1/tickets"), U1, "{\"eventId\":" + seoul2.getId() + ",\"skinId\":999999}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_SKIN_NOT_FOUND"));
        // 색 형식·좌석 길이·eventId 누락
        perform(post("/api/v1/tickets"), U1, "{\"eventId\":" + seoul2.getId() + ",\"accent\":\"red\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        perform(post("/api/v1/tickets"), U1, "{\"eventId\":" + seoul2.getId() + ",\"seat\":\"" + "가".repeat(101) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        perform(post("/api/v1/tickets"), U1, "{}").andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Integer.class)).isEqualTo(1);

        // 다른 사용자는 같은 행사에 자기 티켓을 꽂을 수 있고, 서로의 목록에는 없다. 기록이 없으면 recordId null
        perform(post("/api/v1/tickets"), U2, "{\"eventId\":" + seoul1.getId() + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serial").value(1))
                .andExpect(jsonPath("$.data.recordId").doesNotExist());
        mvc.perform(get("/api/v1/tickets").with(user(U1))).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/v1/tickets")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("목록 순서(행사 날짜 내림차순)·연번(등록 순, 삭제하면 당겨짐), 남의 티켓 수정·삭제는 404")
    void order_serial_and_ownership() throws Exception {
        long first = createTicket(U1, seoul2);
        long second = createTicket(U1, seoul1);
        long third = createTicket(U1, seoul3);

        DocumentContext listed = json(mvc.perform(get("/api/v1/tickets").with(user(U1))).andReturn());
        assertThat(listed.read("$.data[*].id", List.class)).containsExactly((int) third, (int) first, (int) second);
        assertThat(listed.read("$.data[*].serial", List.class)).containsExactly(3, 1, 2);

        // 남의 티켓은 없는 것과 같다
        perform(patch("/api/v1/tickets/{id}", first), U2, "{\"seat\":\"x\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
        mvc.perform(delete("/api/v1/tickets/{id}", first).with(user(U2)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));

        mvc.perform(delete("/api/v1/tickets/{id}", first).with(user(U1))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/tickets/{id}", first).with(user(U1))).andExpect(status().isNotFound());
        listed = json(mvc.perform(get("/api/v1/tickets").with(user(U1))).andReturn());
        assertThat(listed.read("$.data[*].id", List.class)).containsExactly((int) third, (int) second);
        assertThat(listed.read("$.data[*].serial", List.class)).containsExactly(2, 1);
    }

    @Test
    @DisplayName("부분 수정: 키 없음 = 유지, null = 지움, eventId 로 다른 행사로 옮김(null 400, 이미 티켓 있는 행사 409)")
    void patch_semantics() throws Exception {
        long id = json(perform(post("/api/v1/tickets"), U1,
                "{\"eventId\":" + seoul1.getId() + ",\"seat\":\"R석 3열 7번\",\"accent\":\"#112233\"}").andReturn()).read("$.data.id", Long.class);
        createTicket(U1, seoul3);

        perform(patch("/api/v1/tickets/{id}", id), U1, "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seat").value("R석 3열 7번")).andExpect(jsonPath("$.data.accent").value("#112233"));
        perform(patch("/api/v1/tickets/{id}", id), U1, "{\"seat\":null}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seat").doesNotExist()).andExpect(jsonPath("$.data.accent").value("#112233"));
        perform(patch("/api/v1/tickets/{id}", id), U1, "{\"accent\":\"#00FF00\",\"seat\":\"Row K Seat 12\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accent").value("#00ff00")).andExpect(jsonPath("$.data.seat").value("Row K Seat 12"));
        perform(patch("/api/v1/tickets/{id}", id), U1, "{\"accent\":\"#00FF0\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        perform(patch("/api/v1/tickets/{id}", id), U1, "{\"seat\":\"" + "a".repeat(101) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        perform(patch("/api/v1/tickets/{id}", id), U1, "{\"eventId\":null}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        perform(patch("/api/v1/tickets/{id}", id), U1, "{\"eventId\":" + seoul3.getId() + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_ALREADY_EXISTS"));
        perform(patch("/api/v1/tickets/{id}", id), U1, "{\"eventId\":999999}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
        perform(patch("/api/v1/tickets/{id}", id), U1, "{\"eventId\":" + seoul2.getId() + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.event.id").value(seoul2.getId()))
                .andExpect(jsonPath("$.data.event.date").value("2026-09-17"))
                .andExpect(jsonPath("$.data.seat").value("Row K Seat 12"));
        // 비운 행사에는 다시 꽂을 수 있다
        createTicket(U1, seoul1);
    }

    @Test
    @DisplayName("인식: 글자의 날짜 -> 그 날의 행사 후보를 공연장(번역 포함)·시각 점수순으로, 좌석 추출, 내 기록·내 티켓 ID")
    void recognize_matches_events_on_the_date() throws Exception {
        long rid = record(U1, seoul1);
        List<String> interpark = List.of("INTERPARK TICKET", "스타더스트 클럽 전국투어 [우리들의 은하] - 서울", "일시 : 2026년 9월 16일(수) 오후 7시",
                "장소 : 올림픽공원 KSPO DOME", "1층 A구역 12열 5번", "예매번호 T1234567890", "홍길동 님 1매");

        DocumentContext result = json(recognize(U1, interpark).andExpect(status().isOk()).andReturn());
        assertThat(result.read("$.data.matchStatus", String.class)).isEqualTo("MATCHED");
        assertThat(result.read("$.data.detectedDates", List.class)).containsExactly("2026-09-16");
        assertThat(result.read("$.data.seat", String.class)).isEqualTo("1층 A구역 12열 5번");
        assertThat(result.read("$.data.candidates[*].event.id", List.class))
                .containsExactly(seoul1.getId().intValue(), festival.getId().intValue());
        assertThat(result.read("$.data.candidates[0].score", Integer.class)).isEqualTo(100);
        assertThat(result.read("$.data.candidates[0].recordId", Long.class)).isEqualTo(rid);
        assertThat(result.read("$.data.candidates[0].ticketId", Long.class)).isNull();
        assertThat(result.read("$.data.candidates[0].event.venue.name.original", String.class)).isEqualTo("KSPO DOME");
        assertThat(result.read("$.data.candidates[1].recordId", Long.class)).isNull();

        // 꽂은 뒤에는 ticketId 가 붙는다. 일본어 티켓(번역 공연장 이름)도 같은 행사로
        long ticketId = createTicket(U1, seoul1);
        result = json(recognize(U1, List.of("2026年9月16日(水) 18:00開場 19:00開演", "KSPOドーム", "1階 A列 12番")).andReturn());
        assertThat(result.read("$.data.candidates[0].event.id", Long.class)).isEqualTo(seoul1.getId());
        assertThat(result.read("$.data.candidates[0].score", Integer.class)).isEqualTo(100);
        assertThat(result.read("$.data.candidates[0].ticketId", Long.class)).isEqualTo(ticketId);
        assertThat(result.read("$.data.seat", String.class)).isEqualTo("1階 A列 12番");

        // 사흘 연속 공연: 둘째 날 티켓은 둘째 날 행사 하나
        result = json(recognize(U1, List.of("2026.09.17 (목) 19:00", "KSPO DOME", "R석 3열 7번")).andReturn());
        assertThat(result.read("$.data.candidates[*].event.id", List.class)).containsExactly(seoul2.getId().intValue());
        assertThat(result.read("$.data.seat", String.class)).isEqualTo("R석 3열 7번");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tickets", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("인식 실패: 날짜가 없으면 NO_DATE, 그 날 행사가 없으면 NO_EVENT_ON_DATE. 대체 후보 = 참석 표시·기록했지만 티켓이 없는 행사(오늘에 가까운 순)")
    void recognize_fallback() throws Exception {
        long rid = record(U1, seoul2);
        record(U1, seoul1);
        createTicket(U1, seoul1);
        mvc.perform(post("/api/v1/events/{id}/going", upcoming.getId()).with(user(U1))).andExpect(status().isOk());

        DocumentContext noDate = json(recognize(U1, List.of("STARDUST CLUB", "스탠딩 B구역 정리번호 123")).andExpect(status().isOk()).andReturn());
        assertThat(noDate.read("$.data.matchStatus", String.class)).isEqualTo("NO_DATE");
        assertThat(noDate.read("$.data.detectedDates", List.class)).isEmpty();
        assertThat(noDate.read("$.data.seat", String.class)).isEqualTo("스탠딩 B구역 정리번호 123");
        // 티켓이 있는 seoul1 은 빠지고, 오늘에 가까운 upcoming(20일 뒤) 이 seoul2(한 달 전) 보다 먼저
        assertThat(noDate.read("$.data.candidates[*].event.id", List.class))
                .containsExactly(upcoming.getId().intValue(), seoul2.getId().intValue());
        assertThat(noDate.read("$.data.candidates[0].recordId", Long.class)).isNull();
        assertThat(noDate.read("$.data.candidates[1].recordId", Long.class)).isEqualTo(rid);
        assertThat(noDate.read("$.data.candidates[*].score", List.class)).containsOnly(0);

        DocumentContext noEvent = json(recognize(U1, List.of("2026.01.01 19:00")).andReturn());
        assertThat(noEvent.read("$.data.matchStatus", String.class)).isEqualTo("NO_EVENT_ON_DATE");
        assertThat(noEvent.read("$.data.detectedDates", List.class)).containsExactly("2026-01-01");
        assertThat(noEvent.read("$.data.candidates.length()", Integer.class)).isEqualTo(2);

        // 참석·기록이 없는 사용자는 대체 후보도 없다
        recognize(U2, List.of("아무 글자")).andExpect(jsonPath("$.data.matchStatus").value("NO_DATE"))
                .andExpect(jsonPath("$.data.candidates.length()").value(0));
    }

    @Test
    @DisplayName("인식 입력 상한: 1~200줄, 줄당 200자. 넘으면 400 INVALID_INPUT, 비로그인은 401")
    void recognize_limits() throws Exception {
        recognize(U1, Collections.nCopies(200, "x")).andExpect(status().isOk());
        recognize(U1, Collections.nCopies(201, "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        recognize(U1, List.of("가".repeat(201))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        recognize(U1, List.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(post("/api/v1/tickets/recognize").contentType(MediaType.APPLICATION_JSON).content("{\"lines\":null}").with(user(U1)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/tickets/recognize").contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[\"x\"]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("스킨: 공개 목록 = 기본 + 오늘 배포 중인 시즌, 관리자 목록 = 전부, 새 티켓에 시즌 스킨 자동, 기본은 하나·삭제 불가, 지운 스킨의 티켓은 기본으로")
    void skins() throws Exception {
        long base = upsertSkin("{\"name\":\"티켓 1\",\"paper\":\"#F4ECDD\",\"accent\":\"#D94F3D\",\"ink\":\"#2B2118\",\"isDefault\":true}");
        long season = upsertSkin(skinJson("2026 가을 투어", TODAY_UTC.minusDays(1), TODAY_UTC.plusDays(1)));
        upsertSkin(skinJson("지난 시즌", TODAY_UTC.minusDays(30), TODAY_UTC.minusDays(1)));
        upsertSkin(skinJson("다음 시즌", TODAY_UTC.plusDays(1), TODAY_UTC.plusDays(30)));

        DocumentContext listed = json(mvc.perform(get("/api/v1/ticket-skins")).andExpect(status().isOk()).andReturn());
        assertThat(listed.read("$.data[*].id", List.class)).containsExactly((int) base, (int) season);
        assertThat(listed.read("$.data[0].paper", String.class)).isEqualTo("#f4ecdd");
        assertThat(listed.read("$.data[0].isDefault", Boolean.class)).isTrue();
        assertThat(listed.read("$.data[1].season", String.class)).isEqualTo("시즌");
        assertThat(listed.read("$.data[1].availableTo", String.class)).isEqualTo(TODAY_UTC.plusDays(1).toString());
        mvc.perform(get("/api/v1/admin/ticket-skins").with(admin())).andExpect(jsonPath("$.data.length()").value(4));

        // skinId 를 보내지 않으면 시즌 스킨, 보내면 그 스킨
        long auto = json(perform(post("/api/v1/tickets"), U1, "{\"eventId\":" + seoul1.getId() + "}")
                .andExpect(jsonPath("$.data.skinId").value(season)).andReturn()).read("$.data.id", Long.class);
        perform(post("/api/v1/tickets"), U1, "{\"eventId\":" + seoul2.getId() + ",\"skinId\":" + base + "}")
                .andExpect(jsonPath("$.data.skinId").value(base));
        perform(patch("/api/v1/tickets/{id}", auto), U1, "{\"skinId\":null}").andExpect(jsonPath("$.data.skinId").doesNotExist());
        perform(patch("/api/v1/tickets/{id}", auto), U1, "{\"skinId\":" + season + "}").andExpect(jsonPath("$.data.skinId").value(season));

        // 기본은 지울 수 없다. 시즌 스킨을 지우면 그 티켓은 skinId null
        mvc.perform(delete("/api/v1/admin/ticket-skins/{id}", base).with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TICKET_SKIN_DEFAULT"));
        mvc.perform(delete("/api/v1/admin/ticket-skins/{id}", season).with(admin())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT skin_id FROM tickets WHERE id = ?", Long.class, auto)).isNull();
        mvc.perform(delete("/api/v1/admin/ticket-skins/{id}", season).with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_SKIN_NOT_FOUND"));

        // 새 기본을 정하면 이전 기본은 내려간다. isDefault 를 보내지 않은 수정은 기본 여부를 유지한다
        long next = upsertSkin("{\"name\":\"티켓 2\",\"paper\":\"#FFFFFF\",\"accent\":\"#000000\",\"ink\":\"#111111\",\"isDefault\":true}");
        upsertSkin("{\"id\":" + next + ",\"name\":\"티켓 2 개정\",\"paper\":\"#FFFFFF\",\"accent\":\"#000000\",\"ink\":\"#111111\"}");
        DocumentContext all = json(mvc.perform(get("/api/v1/admin/ticket-skins").with(admin())).andReturn());
        assertThat(all.read("$.data[?(@.isDefault == true)].id", List.class)).containsExactly((int) next);
        assertThat(all.read("$.data[?(@.id == " + next + ")].name", List.class)).containsExactly("티켓 2 개정");
    }

    @Test
    @DisplayName("기본 스킨 유일성: DB 가 두 번째 기본을 거부하고, 기존 스킨을 기본으로 올리면 이전 기본이 먼저 내려가며, 기본 0개도 허용")
    void default_skin_is_unique_in_db() throws Exception {
        upsertSkin("{\"name\":\"티켓 1\",\"paper\":\"#F4ECDD\",\"accent\":\"#D94F3D\",\"ink\":\"#2B2118\",\"isDefault\":true}");
        long second = upsertSkin("{\"name\":\"티켓 2\",\"paper\":\"#FFFFFF\",\"accent\":\"#000000\",\"ink\":\"#111111\"}");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ticket_skins (name, paper_color, accent_color, ink_color, is_default, created_at, "
                + "updated_at) VALUES ('우회', '#ffffff', '#000000', '#111111', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 이미 있는 스킨을 기본으로 (UPDATE 끼리의 순서)
        upsertSkin("{\"id\":" + second + ",\"name\":\"티켓 2\",\"paper\":\"#FFFFFF\",\"accent\":\"#000000\",\"ink\":\"#111111\",\"isDefault\":true}");
        assertThat(jdbc.queryForList("SELECT id FROM ticket_skins WHERE is_default", Long.class)).containsExactly(second);

        // 기본을 내리면 0개
        upsertSkin("{\"id\":" + second + ",\"name\":\"티켓 2\",\"paper\":\"#FFFFFF\",\"accent\":\"#000000\",\"ink\":\"#111111\",\"isDefault\":false}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_skins WHERE is_default", Integer.class)).isZero();
    }

    @Test
    @DisplayName("스킨 입력 검증·권한: 색 형식·기간 역전 400, 없는 id 404, 비관리자 403, 공개 목록은 비로그인 200")
    void skin_validation_and_auth() throws Exception {
        perform(put("/api/v1/admin/ticket-skins"), admin(), "{\"name\":\"x\",\"paper\":\"#12345\",\"accent\":\"#000000\",\"ink\":\"#000000\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        perform(put("/api/v1/admin/ticket-skins"), admin(), "{\"name\":\" \",\"paper\":\"#000000\",\"accent\":\"#000000\",\"ink\":\"#000000\"}")
                .andExpect(status().isBadRequest());
        perform(put("/api/v1/admin/ticket-skins"), admin(), skinJson("역전", TODAY_UTC, TODAY_UTC.minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        perform(put("/api/v1/admin/ticket-skins"), admin(), "{\"id\":999999,\"name\":\"x\",\"paper\":\"#000000\",\"accent\":\"#000000\",\"ink\":\"#000000\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_SKIN_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_skins", Integer.class)).isZero();

        perform(put("/api/v1/admin/ticket-skins"), user(U1), skinJson("x", null, null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/ticket-skins").with(user(U1))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/ticket-skins/1").with(user(U1))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ticket-skins")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("UserWithdrawn: 그 사용자의 티켓만 지운다. 티켓이 꽂힌 행사는 FK(RESTRICT) 로 물리 삭제할 수 없다")
    void withdraw_and_event_fk() throws Exception {
        createTicket(U1, seoul1);
        createTicket(U1, seoul2);
        long others = createTicket(U2, seoul1);

        publisher.publishEvent(new UserWithdrawn(U1));

        assertThat(jdbc.queryForList("SELECT id FROM tickets", Long.class)).containsExactly(others);
        mvc.perform(get("/api/v1/tickets").with(user(U2))).andExpect(jsonPath("$.data.length()").value(1));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM events WHERE id = ?", seoul1.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Event show(String name, LocalDate date, int hour, Venue venue) {
        Event event = Event.create(name, SupportedLocale.KO, EventType.CONCERT, date);
        event.applySource(EventLockableField.STARTS_AT, LocalDateTime.of(date.getYear(), date.getMonth(), date.getDayOfMonth(), hour, 0));
        event.applySource(EventLockableField.VENUE, venue);
        return eventRepository.save(event);
    }

    private long record(long userId, Event event) throws Exception {
        mvc.perform(post("/api/v1/records").with(user(userId)).contentType(MediaType.APPLICATION_JSON)
                        .content(recordBody(event.getId(), "[\"HAPPY\"]", false, null, null)))
                .andExpect(status().isOk());
        return row(userId, event.getId()).getId();
    }

    private long createTicket(long userId, Event event) throws Exception {
        return json(perform(post("/api/v1/tickets"), userId, "{\"eventId\":" + event.getId() + "}")
                .andExpect(status().isOk()).andReturn()).read("$.data.id", Long.class);
    }

    private ResultActions recognize(long userId, List<String> lines) throws Exception {
        StringBuilder body = new StringBuilder("{\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            body.append(i == 0 ? "" : ",").append('"').append(lines.get(i).replace("\"", "\\\"")).append('"');
        }
        return perform(post("/api/v1/tickets/recognize"), userId, body.append("]}").toString());
    }

    private long upsertSkin(String body) throws Exception {
        return json(perform(put("/api/v1/admin/ticket-skins"), admin(), body).andExpect(status().isOk()).andReturn())
                .read("$.data.id", Long.class);
    }

    private static String skinJson(String name, LocalDate from, LocalDate to) {
        return "{\"name\":\"" + name + "\",\"season\":\"시즌\",\"paper\":\"#EEEEEE\",\"accent\":\"#AA0000\",\"ink\":\"#111111\""
                + (from == null ? "" : ",\"availableFrom\":\"" + from + "\"")
                + (to == null ? "" : ",\"availableTo\":\"" + to + "\"") + "}";
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, long userId, String body) throws Exception {
        return perform(request, user(userId), body);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(request.with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // 관리자: ADMIN 권한 + 동의한 users 행(쓰기 요청은 동의 게이트를 지난다)
    private static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ADMIN, true), null,
                List.of(new SimpleGrantedAuthority("ADMIN"))));
    }

    private static DocumentContext json(MvcResult result) throws Exception {
        // null 필드는 응답에서 빠지므로 없는 경로는 null 로 읽는다
        return JsonPath.using(Configuration.defaultConfiguration().addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL))
                .parse(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
