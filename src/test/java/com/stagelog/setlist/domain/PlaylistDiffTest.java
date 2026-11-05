package com.stagelog.setlist.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.setlist.domain.PlaylistDiff.Delete;
import com.stagelog.setlist.domain.PlaylistDiff.Insert;
import com.stagelog.setlist.domain.PlaylistDiff.Item;
import com.stagelog.setlist.domain.PlaylistDiff.Move;
import com.stagelog.setlist.domain.PlaylistDiff.Op;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlaylistDiffTest {

    @Test
    @DisplayName("명세 예시: ABCD→BCDA 이동 1, ABCD→ABXD 삭제 1 + 삽입 1, ABCD→ABCDB 두 번째 B 삽입 1")
    void spec_examples() {
        assertThat(PlaylistDiff.plan(items("A", "B", "C", "D"), List.of("B", "C", "D", "A")))
                .containsExactly(new Move("i0", "A", 3));
        assertThat(PlaylistDiff.plan(items("A", "B", "C", "D"), List.of("A", "B", "X", "D")))
                .containsExactly(new Delete("i2", "C"), new Insert("X", 2));
        assertThat(PlaylistDiff.plan(items("A", "B", "C", "D"), List.of("A", "B", "C", "D", "B")))
                .containsExactly(new Insert("B", 4));
    }

    @Test
    @DisplayName("position 은 목표상 앞 항목의 현재 위치 + 1 (이동은 그 항목을 뺀 목록 기준), 앞 항목이 없으면 0")
    void positions() {
        List<Item> current = items("A", "B", "C", "D");
        PlaylistDiff.Simulation simulation = new PlaylistDiff.Simulation(current, List.of("B", "C", "D", "A"));
        assertThat(simulation.position(new Move("i0", "A", 3))).isEqualTo(3);

        simulation = new PlaylistDiff.Simulation(current, List.of("D", "A", "B", "C"));
        assertThat(simulation.position(new Move("i3", "D", 0))).isZero();
    }

    @Test
    @DisplayName("무작위 1,000건(삭제·추가·순서 변경·중복 영상): 계획을 로컬에 적용하면 목표와 같다")
    void random_plans_reach_target() {
        Random random = new Random(20261105);
        for (int n = 0; n < 1_000; n++) {
            List<String> current = randomList(random);
            List<String> target = randomList(random);
            List<Item> items = new ArrayList<>();
            for (int i = 0; i < current.size(); i++) {
                items.add(new Item("i" + i, current.get(i)));
            }
            List<Op> ops = PlaylistDiff.plan(items, target);
            assertThat(PlaylistDiff.reachesTarget(items, target, ops)).as("current=%s target=%s ops=%s", current, target, ops).isTrue();
        }
    }

    private static List<String> randomList(Random random) {
        List<String> out = new ArrayList<>();
        int size = random.nextInt(25);
        for (int i = 0; i < size; i++) {
            out.add("v" + random.nextInt(10));
        }
        return out;
    }

    private static List<Item> items(String... videoIds) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < videoIds.length; i++) {
            items.add(new Item("i" + i, videoIds[i]));
        }
        return items;
    }
}
