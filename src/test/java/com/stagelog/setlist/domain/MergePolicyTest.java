package com.stagelog.setlist.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.stagelog.setlist.domain.MergePolicy.Entry;
import com.stagelog.setlist.domain.MergePolicy.Result;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M1~M3: 합집합·setlist.fm 순서 우선·위키 전용 곡 뒤 유지·멱등.
 */
class MergePolicyTest {

    static final long A = 1;
    static final long B = 2;
    static final long C = 3;
    static final long D = 4;

    static List<Entry> wiki(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(id -> new Entry(id, false)).toList();
    }

    @Test
    @DisplayName("M1: 위키 [A,B,C] + fm [B,D,A] -> B1 D2 A3 C4, in_setlistfm 은 B·D·A true, C false")
    void union_fm_order_first() {
        Result r = MergePolicy.merge(wiki(A, B, C), List.of(B, D, A));

        assertThat(r.changed()).isTrue();
        assertThat(r.entries()).containsExactly(new Entry(B, true), new Entry(D, true), new Entry(A, true), new Entry(C, false));
    }

    @Test
    @DisplayName("M2: fm 이 비어 있으면 위키 그대로, changed=false")
    void empty_fm_keeps_wiki() {
        Result r = MergePolicy.merge(wiki(A, B, C), List.of());

        assertThat(r.changed()).isFalse();
        assertThat(r.entries()).isEqualTo(wiki(A, B, C));
    }

    @Test
    @DisplayName("M3: 같은 입력을 두 번 넣으면 두 번째는 changed=false (멱등)")
    void idempotent() {
        Result first = MergePolicy.merge(wiki(A, B, C), List.of(B, D, A));
        Result second = MergePolicy.merge(first.entries(), List.of(B, D, A));

        assertThat(second.changed()).isFalse();
        assertThat(second.entries()).isEqualTo(first.entries());
    }

    @Test
    @DisplayName("위키 전용 곡의 상대 순서를 지키고, fm 의 중복 곡은 한 번만")
    void wiki_only_relative_order_and_fm_dedupe() {
        Result r = MergePolicy.merge(wiki(C, A, D), List.of(B, B));

        assertThat(r.entries()).containsExactly(new Entry(B, true), new Entry(C, false), new Entry(A, false), new Entry(D, false));
    }
}
