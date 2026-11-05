package com.stagelog.setlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.setlist.application.QuotaScope;
import com.stagelog.setlist.application.SyncPlaylistUseCase;
import com.stagelog.setlist.domain.QuotaOperation;
import com.stagelog.setlist.support.FakeYouTubeIntegrationTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Quota 원장 수용 기준: 동시 예약 100건에서 한도 초과 0, quotaExceeded 뒤 그날 호출 0·알림 1, 예산 부족 시 낮은 우선순위부터 DEFERRED, 관리자 조회 API.
 */
class YouTubeQuotaIntegrationTest extends FakeYouTubeIntegrationTest {

    static final long ADMIN = 9L;

    @Autowired
    SyncPlaylistUseCase syncPlaylist;
    @Autowired
    WebApplicationContext context;

    @BeforeEach
    void setUp() {
        FAKE.reset();
        connectChannel();
        resetQuota(1_000_000, 1_000_000);
    }

    @Test
    @DisplayName("한도 1,000 에 1~3건(50~150 unit) 예약 100건을 동시에 보내도 성공한 예약 합계가 한도를 넘지 않고, 일일 행의 예약 합계와 같다")
    void concurrent_reservations_never_exceed_limit() throws Exception {
        resetQuota(1_000_000, 1_000);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int writes = 1 + i % 3;
            tasks.add(() -> {
                start.await();
                try {
                    quota.reserve(QuotaScope.manual(null, null), Collections.nCopies(writes, QuotaOperation.INSERT));
                    return writes * 50;
                } catch (ApplicationException e) {
                    return 0;
                }
            });
        }
        int reserved = 0;
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            tasks.forEach(task -> futures.add(pool.submit(task)));
            start.countDown();
            for (Future<Integer> future : futures) {
                reserved += future.get();
            }
        } finally {
            pool.shutdown();
        }
        System.out.printf("[JAN-03] concurrentReservations=100 reservedUnits=%d limit=1000%n", reserved);
        assertThat(reserved).isLessThanOrEqualTo(1_000).isPositive();
        assertThat(quotaColumn("reserved_units", "MANUAL")).isEqualTo(reserved);
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(units), 0) FROM api_quota_ledger WHERE status = 'RESERVED'", Integer.class)).isEqualTo(reserved);
    }

    @Test
    @DisplayName("API 가 quotaExceeded 를 주면 그날 모든 bucket 에 exhausted_at, run DEFERRED, 알림 1회. 이후 그날 동기화는 유튜브를 한 번도 부르지 않는다")
    void quota_exceeded_stops_the_day() {
        Long first = eventWithChangedSetlist("PLQ1", today().plusDays(30));
        Long second = eventWithChangedSetlist("PLQ2", today());
        FAKE.quotaExceeded(true);

        assertThatThrownBy(() -> syncPlaylist.syncOrThrow(first)).isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getErrorCode()).isEqualTo(ErrorCode.YOUTUBE_QUOTA_EXHAUSTED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_quota_daily WHERE quota_date = ? AND exhausted_at IS NULL",
                Integer.class, quota.today())).isZero();

        FAKE.quotaExceeded(false);
        FAKE.clearCalls();
        syncPlaylist.syncIfConnected(second);
        assertThatThrownBy(() -> syncPlaylist.syncOrThrow(first)).isInstanceOf(ApplicationException.class);
        syncPlaylist.retryDeferred();

        assertThat(FAKE.calls()).isEmpty();
        ArgumentCaptor<String> alert = ArgumentCaptor.forClass(String.class);
        verify(alertPort, times(1)).send(alert.capture());
        assertThat(alert.getValue()).contains("[유튜브 할당량 소진]", "quotaExceeded");
        assertThat(jdbc.queryForList("SELECT status FROM sync_runs WHERE source = 'YOUTUBE' ORDER BY id DESC LIMIT 4", String.class))
                .containsOnly("DEFERRED");
        assertThat(deferred(first)).isTrue();
        assertThat(deferred(second)).isTrue();
    }

    @Test
    @DisplayName("예산 부족: 자동 동기화는 낮은 우선순위일수록 작은 몫까지만 예약해, 같은 잔량에서 7일 밖 공연은 DEFERRED 이고 오늘 공연은 반영된다")
    void low_priority_defers_first_on_immediate_sync() {
        resetQuota(1_000, 1_000_000);
        jdbc.update("UPDATE api_quota_daily SET used_units = 600 WHERE quota_date = ? AND bucket = 'AUTO'", quota.today());
        Long later = eventWithChangedSetlist("PLL", today().plusDays(30));
        Long showDay = eventWithChangedSetlist("PLT", today());

        syncPlaylist.syncIfConnected(later);
        syncPlaylist.syncIfConnected(showDay);

        assertThat(deferred(later)).isTrue();
        assertThat(FAKE.videoIds("PLL")).isNotEqualTo(target());
        assertThat(deferred(showDay)).isFalse();
        assertThat(FAKE.videoIds("PLT")).isEqualTo(target());
    }

    @Test
    @DisplayName("리셋 뒤 재시도: 미룬 공연 3개를 우선순위 순서로 처리하고, 예산이 하나만큼만 있으면 오늘 공연만 반영되고 7일 안·나머지는 다시 DEFERRED")
    void retry_deferred_in_priority_order() {
        // 12곡 역순 → 이동 11건(550) + 조회 3 = 553 unit. 한도 1,000 에는 한 건만 들어간다.
        // 나머지(상한 60% = 600)를 먼저 처리하면 그것이 성공하고 오늘 공연이 굶는다 — 순서가 결과를 정한다
        resetQuota(1_000, 1_000_000);
        List<String> twelve = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            twelve.add(String.format("v%02d", i));
        }
        List<String> reversed = new ArrayList<>(twelve);
        Collections.reverse(reversed);
        Long later = eventWithSetlist("PLA", today().plusDays(30), reversed, twelve);
        Long week = eventWithSetlist("PLB", today().plusDays(3), reversed, twelve);
        Long showDay = eventWithSetlist("PLC", today(), reversed, twelve);
        jdbc.update("UPDATE setlist_pages SET youtube_sync_deferred_at = UTC_TIMESTAMP(6) WHERE event_id IN (?, ?, ?)", later, week, showDay);

        syncPlaylist.retryDeferred();

        assertThat(FAKE.videoIds("PLC")).isEqualTo(twelve);
        assertThat(deferred(showDay)).isFalse();
        assertThat(deferred(week)).isTrue();
        assertThat(deferred(later)).isTrue();
        assertThat(quotaColumn("used_units", "AUTO") + quotaColumn("reserved_units", "AUTO")).isLessThanOrEqualTo(1_000);
    }

    @Test
    @DisplayName("GET /admin/youtube/quota?date= : bucket 별 한도·사용·예약과 행사별 사용 상위를 돌려준다")
    void admin_quota_api() throws Exception {
        Long eventId = eventWithChangedSetlist("PLD", today());
        syncPlaylist.syncOrThrow(eventId);
        insertAdmin();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        LocalDate date = quota.today();
        int used = quotaColumn("used_units", "MANUAL");

        mvc.perform(get("/api/v1/admin/youtube/quota").param("date", date.toString())
                        .with(authentication(new UsernamePasswordAuthenticationToken(new AuthenticatedUser(ADMIN, true), null,
                                List.of(new SimpleGrantedAuthority("ADMIN"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(date.toString()))
                .andExpect(jsonPath("$.data.buckets[0].bucket").value("AUTO"))
                .andExpect(jsonPath("$.data.buckets[1].bucket").value("MANUAL"))
                .andExpect(jsonPath("$.data.buckets[1].usedUnits").value(used))
                .andExpect(jsonPath("$.data.buckets[1].reservedUnits").value(0))
                .andExpect(jsonPath("$.data.topEvents[0].eventId").value(eventId))
                .andExpect(jsonPath("$.data.topEvents[0].units").value(used));
        // 1곡 교체 = 쓰기 2(100) + 재생목록 확인 1 + 목록 1 + 반영 확인 목록 1
        assertThat(used).isEqualTo(103);
    }

    // 목표는 v01~v10 에서 5번째를 v11 로 바꾼 목록. Playlist 는 원래 v01~v10 이다 (1곡 교체 = 쓰기 2건)
    private Long eventWithChangedSetlist(String playlistId, LocalDate date) {
        List<String> original = new ArrayList<>(target());
        original.set(4, "v05");
        return eventWithSetlist(playlistId, date, original, target());
    }

    private Long eventWithSetlist(String playlistId, LocalDate date, List<String> current, List<String> setlist) {
        Long eventId = newEvent("Quota 행사 " + playlistId, date);
        List<Long> songIds = new ArrayList<>();
        for (String video : setlist) {
            songIds.add(newSong("Quota 곡 " + eventId + " " + video, video));
        }
        FAKE.seed(playlistId, "t", current);
        writeSetlist(eventId, playlistId, songIds);
        return eventId;
    }

    private static List<String> target() {
        List<String> videos = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            videos.add(i == 5 ? "v11" : String.format("v%02d", i));
        }
        return videos;
    }

    private boolean deferred(Long eventId) {
        return jdbc.queryForObject("SELECT youtube_sync_deferred_at IS NOT NULL FROM setlist_pages WHERE event_id = ?", Boolean.class, eventId);
    }

    private void insertAdmin() {
        jdbc.update("INSERT IGNORE INTO users (id, nickname, locale, is_admin, consented_at, terms_version, created_at, updated_at) "
                + "VALUES (?, 'admin9', 'ko', TRUE, UTC_TIMESTAMP(6), 'v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", ADMIN);
    }

}
