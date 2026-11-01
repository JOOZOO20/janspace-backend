package com.stagelog.attendance.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.attendance.domain.Attendance;
import com.stagelog.attendance.support.AttendanceIntegrationTest;
import com.stagelog.event.domain.Event;
import com.stagelog.event.domain.GeoPoint;
import com.stagelog.event.domain.Venue;
import com.stagelog.event.domain.VenueLockableField;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class AttendanceConcurrencyIntegrationTest extends AttendanceIntegrationTest {

    private static final long USER = 9001L;

    @Autowired
    AttendanceCommand commands;
    @Autowired
    DistanceRecalculation recalculation;
    @Autowired
    TransactionTemplate transactions;

    @Test
    @DisplayName("갈래요 취소와 기록 생성이 겹쳐도 생성한 기록이 삭제되지 않음")
    void unplan_does_not_delete_concurrent_record() throws Exception {
        insertUser(USER, SEOUL);
        Event show = event("취소·기록 경합", TODAY.minusDays(2), venue("공연장", "서울", "KR", KSPO));
        commands.plan(USER, show.getId());

        raceBeforeCommit(() -> commands.unplan(USER, show.getId()),
                () -> commands.create(USER, new RecordInput(show.getId(), List.of("HAPPY"), null,
                        "보존할 기록", false, false, null)));

        Attendance result = row(USER, show.getId());
        assertThat(result.isDeleted()).isFalse();
        assertThat(result.isStar()).isTrue();
        assertThat(result.getMemo()).isEqualTo("보존할 기록");
    }

    @Test
    @DisplayName("메모 수정의 커밋이 새로 재계산한 거리를 이전 값으로 덮어쓰지 않음")
    void memo_update_preserves_concurrent_distance_recalculation() throws Exception {
        insertUser(USER, SEOUL);
        Venue hall = venue("이동할 공연장", "서울", "KR", KSPO);
        Event show = event("메모·거리 경합", TODAY.minusDays(2), hall);
        Attendance saved = commands.create(USER, new RecordInput(show.getId(), List.of("HAPPY"), null,
                "이전 메모", false, false, null));
        assertThat(saved.getDistanceKm().doubleValue()).isBetween(20.0, 40.0);

        raceBeforeCommit(() -> commands.update(USER, saved.getId(), null, null, Optional.of("새 메모")), () -> {
            hall.adminUpdate(VenueLockableField.COORDINATES, new GeoPoint(SINGAPORE[0], SINGAPORE[1]));
            venueRepository.save(hall);
            recalculation.forUser(USER);
        });

        Attendance result = row(USER, show.getId());
        assertThat(result.getMemo()).isEqualTo("새 메모");
        assertThat(result.getDistanceKm().doubleValue()).isBetween(9200.0, 9500.0);
    }

    // 첫 변경을 아직 커밋하지 않은 상태에서 두 번째 쓰기를 실행한다.
    // 잠금이 있으면 두 번째는 대기하고, 빠져 있으면 먼저 커밋하여 첫 변경의 stale UPDATE 에 노출된다.
    private void raceBeforeCommit(Runnable firstChange, Runnable secondChange) throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<?> first = pool.submit(() -> transactions.executeWithoutResult(status -> {
                    firstChange.run();
                    changed.countDown();
                    await(commit);
                }));
                assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
                Future<?> second = pool.submit(secondChange);
                try {
                    second.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException expected) {
                    // 같은 사용자 잠금을 기다리는 정상 경로. 첫 커밋 뒤 두 작업의 결과를 모두 확인한다.
                } finally {
                    commit.countDown();
                }
                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                commit.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 요청 동기화 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
