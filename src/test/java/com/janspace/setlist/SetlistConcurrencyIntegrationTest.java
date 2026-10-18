package com.janspace.setlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.janspace.setlist.application.EditSetlistUseCase;
import com.janspace.setlist.application.MergeSetlistFmUseCase;
import com.janspace.setlist.application.port.FmSetlist;
import com.janspace.setlist.support.SetlistIntegrationTest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * #37 같은 행사 동시 편집. 두 스레드를 래치로 동시에 출발시켜 둘 다 성공하고 리비전 번호가 겹치지 않는지 본다.
 */
class SetlistConcurrencyIntegrationTest extends SetlistIntegrationTest {

    static final int ROUNDS = 10;

    @Autowired
    EditSetlistUseCase editUseCase;
    @Autowired
    MergeSetlistFmUseCase mergeUseCase;

    Long a;
    Long b;
    Long c;

    @BeforeEach
    void setUp() {
        // 이 클래스는 테이블을 비우지 않는 CatalogIntegrationTest 보다 먼저 돌 수 있어 그 테스트와 겹치지 않는 제목·별칭을 쓴다
        a = newSong("동시 편집 곡 A", "concurrent song a", "vidA");
        b = newSong("동시 편집 곡 B", "concurrent song b", "vidB");
        c = newSong("동시 편집 곡 C", "concurrent song c", "vidC");
    }

    @Test
    @DisplayName("페이지가 있는 행사를 두 사용자가 동시에 편집하면 둘 다 성공하고 버전이 1씩 올라간다")
    void concurrent_edits_on_existing_page() throws Exception {
        Long eventId = newEvent("잔나비 단독 공연", today.plusDays(1));
        editUseCase.edit(eventId, USER, List.of(a), null);
        for (int i = 0; i < ROUNDS; i++) {
            race(() -> editUseCase.edit(eventId, USER, List.of(a, b), null),
                    () -> editUseCase.edit(eventId, OTHER, List.of(c, b), null));
        }
        assertConsistent(eventId, 1 + 2 * ROUNDS);
    }

    @Test
    @DisplayName("페이지가 없는 행사의 첫 편집 두 건이 동시에 와도 둘 다 성공한다")
    void concurrent_first_edits_create_page_once() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            Long eventId = newEvent("잔나비 단독 공연 " + i, today.plusDays(1));
            race(() -> editUseCase.edit(eventId, USER, List.of(a, b), null),
                    () -> editUseCase.edit(eventId, OTHER, List.of(c), null));
            assertConsistent(eventId, 2);
        }
    }

    @Test
    @DisplayName("setlist.fm 을 받아오는 동안 커밋된 팬 편집을 병합이 옛 항목으로 덮어쓰지 않는다")
    void merge_keeps_edit_committed_during_fetch() throws Exception {
        Long eventId = newEventLinkedToSetlistFm("잔나비 단독 공연", today.minusDays(1), "fm1");
        editUseCase.edit(eventId, USER, List.of(a), null);
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch edited = new CountDownLatch(1);
        when(setlistFmPort.fetch("fm1")).thenAnswer(invocation -> {
            fetching.countDown();
            edited.await(10, TimeUnit.SECONDS);
            return Optional.of(new FmSetlist("fm1", "v1", today.minusDays(1), List.of("concurrent song b")));
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // 병합 트랜잭션은 행사 조회로 스냅샷을 연 뒤 setlist.fm 응답을 기다린다. 그사이 팬 편집 [A] -> [A, C] 가 커밋된다
            Future<Boolean> merge = pool.submit(() -> mergeUseCase.merge(eventId));
            assertThat(fetching.await(10, TimeUnit.SECONDS)).isTrue();
            editUseCase.edit(eventId, OTHER, List.of(a, c), null);
            edited.countDown();
            assertThat(merge.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(itemSongIds(eventId)).containsExactlyInAnyOrder(a, b, c);
        assertConsistent(eventId, 3);
    }

    // 두 작업을 동시에 출발시키고 둘 다 예외 없이 끝나기를 기다린다
    private static void race(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = List.of(first, second).stream().<Future<?>>map(task -> pool.submit(() -> {
                ready.countDown();
                start.await();
                return task.call();
            })).toList();
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // 리비전 번호는 1..n 빈틈 없이, 페이지 version = n, 현재 항목 = 마지막 리비전 스냅샷
    private void assertConsistent(Long eventId, int expectedVersion) {
        List<Integer> versions = jdbc.queryForList("SELECT version FROM setlist_revisions WHERE event_id = ? ORDER BY version",
                Integer.class, eventId);
        assertThat(versions).hasSize(expectedVersion);
        for (int v = 1; v <= expectedVersion; v++) {
            assertThat(versions.get(v - 1)).isEqualTo(v);
        }
        assertThat(jdbc.queryForObject("SELECT version FROM setlist_pages WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(expectedVersion);
        String lastSnapshot = jdbc.queryForObject(
                "SELECT JSON_EXTRACT(snapshot, '$[*].songId') FROM setlist_revisions WHERE event_id = ? AND version = ?",
                String.class, eventId, expectedVersion);
        assertThat(lastSnapshot.replace(" ", "")).isEqualTo(itemSongIds(eventId).toString().replace(" ", ""));
    }
}
