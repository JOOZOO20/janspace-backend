package com.stagelog.setlist;

import com.stagelog.setlist.application.SyncPlaylistUseCase;
import com.stagelog.setlist.support.FakeYouTubeIntegrationTest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * JAN-03 측정: 20곡 셋리스트의 변경 시나리오 5개에서 관리자 수동 동기화 1회가 Fake YouTube 서버에 보내는 쓰기·조회 수 (docs/measurements/JAN-03.md).
 * 판정은 리포트에서 한다. 이 테스트는 수치만 출력하고 실패하지 않는다(수정 전 코드에서도 그대로 돈다).
 */
@Tag("measurement")
class Jan03PlaylistWritesMeasurementTest extends FakeYouTubeIntegrationTest {

    static final int SONGS = 20;
    static final int REPEATS = 3;
    static final int WRITE_UNITS = 50;
    static final int DAILY_UNITS = 10_000;

    record Scenario(String name, UnaryOperator<List<Integer>> change) {
    }

    @Autowired
    SyncPlaylistUseCase syncPlaylist;

    @Test
    @DisplayName("JAN-03 시나리오별 동기화 1회의 쓰기 횟수")
    void measure() {
        connectChannel();
        // 곡 번호 1~20 = v01~v20, 21 = 새 곡 v21, 22 = v01 과 같은 영상의 앵콜 곡 (셋리 항목은 행사당 곡이 유일해야 한다)
        List<Scenario> scenarios = List.of(
                new Scenario("S1 변경 없음", base -> base),
                new Scenario("S2 1곡 교체", base -> replace(base, 9, 21)),
                new Scenario("S3 앵콜 1곡 추가", base -> append(base, 22)),
                new Scenario("S4 마지막 곡 삭제", base -> base.subList(0, base.size() - 1)),
                new Scenario("S5 두 곡 위치 맞바꿈", base -> swap(base, 4, 14)));
        List<String> lines = new ArrayList<>();
        long s2WriteUnits = -1;
        long s2TotalUnits = -1;
        for (Scenario scenario : scenarios) {
            for (int run = 1; run <= REPEATS; run++) {
                Long eventId = newEvent("측정 행사 " + scenario.name() + " " + run, today().plusDays(30));
                List<Long> songIds = new ArrayList<>();
                for (int i = 1; i <= SONGS + 2; i++) {
                    songIds.add(newSong("측정곡 " + eventId + "-" + i, i == SONGS + 2 ? video(1) : video(i)));
                }
                List<Integer> base = new ArrayList<>();
                for (int i = 1; i <= SONGS; i++) {
                    base.add(i);
                }
                List<Integer> target = scenario.change().apply(base);
                String playlistId = "PLM" + eventId;
                FAKE.seed(playlistId, "측정 재생목록", base.stream().map(Jan03PlaylistWritesMeasurementTest::video).toList());
                writeSetlist(eventId, playlistId, target.stream().map(n -> songIds.get(n - 1)).toList());
                FAKE.clearCalls();

                String error = "";
                try {
                    syncPlaylist.syncOrThrow(eventId);
                } catch (RuntimeException e) {
                    error = " error=" + e.getClass().getSimpleName() + ":" + e.getMessage();
                }
                long writes = FAKE.itemWrites();
                long reads = FAKE.reads();
                List<String> expected = target.stream().map(n -> video(n == SONGS + 2 ? 1 : n)).toList();
                boolean match = FAKE.videoIds(playlistId).equals(expected);
                lines.add(String.format("[JAN-03] scenario=%s run=%d writes=%d writeUnits=%d reads=%d readUnits=%d totalUnits=%d match=%s%s",
                        scenario.name(), run, writes, writes * WRITE_UNITS, reads, reads, writes * WRITE_UNITS + reads, match, error));
                if (scenario.name().startsWith("S2") && run == 1) {
                    s2WriteUnits = writes * WRITE_UNITS;
                    s2TotalUnits = writes * WRITE_UNITS + reads;
                }
            }
        }
        lines.forEach(System.out::println);
        System.out.printf("[JAN-03] dailyCapacity(S2, writeUnits)=%s dailyCapacity(S2, writeUnits+readUnits)=%s%n",
                s2WriteUnits > 0 ? DAILY_UNITS / s2WriteUnits : "n/a", s2TotalUnits > 0 ? DAILY_UNITS / s2TotalUnits : "n/a");
    }

    static String video(int n) {
        return String.format("v%02d", n);
    }

    static List<Integer> replace(List<Integer> base, int index, int song) {
        List<Integer> out = new ArrayList<>(base);
        out.set(index, song);
        return out;
    }

    static List<Integer> append(List<Integer> base, int song) {
        List<Integer> out = new ArrayList<>(base);
        out.add(song);
        return out;
    }

    static List<Integer> swap(List<Integer> base, int i, int j) {
        List<Integer> out = new ArrayList<>(base);
        Collections.swap(out, i, j);
        return out;
    }
}
