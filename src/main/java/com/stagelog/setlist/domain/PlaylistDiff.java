package com.stagelog.setlist.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 재생목록 현재 항목을 목표 영상 순서로 바꾸는 최소 연산 계산 (순수 계산, 외부 호출 없음).
 * 같은 영상이 여러 번 나올 수 있어 항목 key 는 (영상 ID, 그 영상의 등장 순번)이다.
 * 1. 삭제: 현재에만 있는 key 2. 유지: 남은 공통 key 중 LCS(최장 공통 부분 수열)에 든 항목은 움직이지 않음
 * 3. 이동·삽입: 목표 순서대로 앞에서부터, LCS 밖 공통 항목은 이동(update position), 목표에만 있는 항목은 삽입
 * position 은 계획에 고정하지 않고 적용 직전에 Simulation 이 계산한다: 목표상 바로 앞 항목의 현재 위치 + 1 (앞 항목이 없으면 0).
 * 이동은 그 항목을 뺀 목록에서의 위치, 즉 이동 뒤 결과 목록에서의 0 기반 위치다 (playlistItems.update 의 snippet.position).
 */
public final class PlaylistDiff {

    private PlaylistDiff() {
    }

    // playlistItems.list 로 읽은 현재 항목 (playlistItemId, videoId)
    public record Item(String itemId, String videoId) {
    }

    public sealed interface Op permits Delete, Move, Insert {
    }

    public record Delete(String itemId, String videoId) implements Op {
    }

    // targetIndex = 목표 목록에서의 자리. 앞 항목을 찾는 데만 쓴다
    public record Move(String itemId, String videoId, int targetIndex) implements Op {
    }

    public record Insert(String videoId, int targetIndex) implements Op {
    }

    record Key(String videoId, int occurrence) {
    }

    public static List<Op> plan(List<Item> current, List<String> target) {
        List<Key> currentKeys = keys(current.stream().map(Item::videoId).toList());
        List<Key> targetKeys = keys(target);
        Set<Key> inTarget = new HashSet<>(targetKeys);
        Map<Key, Item> currentByKey = new HashMap<>();
        List<Key> commonInCurrentOrder = new ArrayList<>();
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            Key key = currentKeys.get(i);
            currentByKey.put(key, current.get(i));
            if (inTarget.contains(key)) {
                commonInCurrentOrder.add(key);
            } else {
                ops.add(new Delete(current.get(i).itemId(), current.get(i).videoId()));
            }
        }
        List<Key> commonInTargetOrder = targetKeys.stream().filter(currentByKey::containsKey).toList();
        Set<Key> kept = lcs(commonInCurrentOrder, commonInTargetOrder);
        for (int j = 0; j < target.size(); j++) {
            Key key = targetKeys.get(j);
            Item existing = currentByKey.get(key);
            if (existing == null) {
                ops.add(new Insert(key.videoId(), j));
            } else if (!kept.contains(key)) {
                ops.add(new Move(existing.itemId(), existing.videoId(), j));
            }
        }
        return ops;
    }

    // API 호출 전 검증: 계획을 로컬 목록에 그대로 적용한 결과가 목표와 같은가
    public static boolean reachesTarget(List<Item> current, List<String> target, List<Op> ops) {
        Simulation simulation = new Simulation(current, target);
        for (Op op : ops) {
            simulation.apply(op, op instanceof Insert insert ? "simulated-" + insert.targetIndex() : null);
        }
        return simulation.videoIds().equals(target);
    }

    static List<Key> keys(List<String> videoIds) {
        Map<String, Integer> seen = new HashMap<>();
        List<Key> keys = new ArrayList<>(videoIds.size());
        for (String videoId : videoIds) {
            int occurrence = seen.merge(videoId, 1, Integer::sum);
            keys.add(new Key(videoId, occurrence));
        }
        return keys;
    }

    // 두 목록 모두 key 가 서로 다르므로 LCS 를 key 집합으로 돌려준다. O(n*m), 셋리 길이(수십 곡)에서는 충분하다
    static Set<Key> lcs(List<Key> a, List<Key> b) {
        int[][] length = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                length[i][j] = a.get(i).equals(b.get(j)) ? length[i + 1][j + 1] + 1 : Math.max(length[i + 1][j], length[i][j + 1]);
            }
        }
        Set<Key> kept = new HashSet<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            if (a.get(i).equals(b.get(j))) {
                kept.add(a.get(i));
                i++;
                j++;
            } else if (length[i + 1][j] >= length[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
        return kept;
    }

    /**
     * 연산을 하나씩 적용하는 로컬 목록. 실제 호출 직전에 position 을 묻고, 호출이 성공하면 같은 연산을 적용한다.
     * 삽입을 건너뛰면(videoNotFound) 적용하지 않으면 된다. 뒤 연산은 남은 항목 중 목표상 가장 가까운 앞 항목을 기준으로 위치를 다시 계산한다.
     */
    public static final class Simulation {

        // targetIndex -1 = 목표에 없는 항목(삭제 대상)
        private record Entry(String itemId, String videoId, int targetIndex) {
        }

        private final List<Entry> entries = new ArrayList<>();

        public Simulation(List<Item> current, List<String> target) {
            Map<Key, Integer> targetIndex = new HashMap<>();
            List<Key> targetKeys = keys(target);
            for (int j = 0; j < targetKeys.size(); j++) {
                targetIndex.put(targetKeys.get(j), j);
            }
            List<Key> currentKeys = keys(current.stream().map(Item::videoId).toList());
            for (int i = 0; i < current.size(); i++) {
                entries.add(new Entry(current.get(i).itemId(), current.get(i).videoId(), targetIndex.getOrDefault(currentKeys.get(i), -1)));
            }
        }

        // 이동·삽입할 항목의 결과 목록 위치. 이동은 그 항목을 뺀 목록 기준이다
        public int position(Op op) {
            return switch (op) {
                case Move move -> positionAfterPredecessor(without(move.itemId()), move.targetIndex());
                case Insert insert -> positionAfterPredecessor(entries, insert.targetIndex());
                case Delete delete -> throw new IllegalArgumentException("삭제에는 위치가 없습니다");
            };
        }

        // insertedItemId 는 삽입 응답의 playlistItemId (다른 연산에는 null)
        public void apply(Op op, String insertedItemId) {
            switch (op) {
                case Delete delete -> entries.remove(indexOf(delete.itemId()));
                case Move move -> {
                    int at = position(move);
                    Entry entry = entries.remove(indexOf(move.itemId()));
                    entries.add(at, entry);
                }
                case Insert insert -> entries.add(position(insert), new Entry(insertedItemId, insert.videoId(), insert.targetIndex()));
            }
        }

        public List<String> videoIds() {
            return entries.stream().map(Entry::videoId).toList();
        }

        private static int positionAfterPredecessor(List<Entry> list, int targetIndex) {
            int best = -1;
            int bestTarget = -1;
            for (int i = 0; i < list.size(); i++) {
                int t = list.get(i).targetIndex();
                if (t < targetIndex && t > bestTarget) {
                    best = i;
                    bestTarget = t;
                }
            }
            return best + 1;
        }

        private List<Entry> without(String itemId) {
            return entries.stream().filter(e -> !e.itemId().equals(itemId)).toList();
        }

        private int indexOf(String itemId) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).itemId().equals(itemId)) {
                    return i;
                }
            }
            throw new IllegalArgumentException("시뮬레이션 목록에 없는 항목입니다. itemId=" + itemId);
        }
    }
}
