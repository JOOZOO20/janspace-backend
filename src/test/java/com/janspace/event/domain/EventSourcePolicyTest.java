package com.janspace.event.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.event.domain.EventSourcePolicy.Current;
import com.janspace.event.domain.EventSourcePolicy.Fold;
import com.janspace.event.domain.EventSourcePolicy.OccurrenceDiff;
import com.janspace.event.domain.EventSourcePolicy.Proposal;
import com.janspace.event.domain.EventSourcePolicy.Rejected;
import com.janspace.event.domain.EventSourcePolicy.SourceSnapshot;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 접기 정책. Spring 없음.
 */
class EventSourcePolicyTest {

    static final LocalDate DATE = LocalDate.of(2026, 10, 23);

    static SourceSnapshot snapshot(String name, LocalDateTime startsAt, Integer runningMinutes) {
        return new SourceSnapshot("x", DATE.toString(), name, null, null, null, null, startsAt, DATE, runningMinutes);
    }

    static Current current(String name, LocalDateTime startsAt, Long venueId, Set<EventLockableField> locked) {
        return new Current(name, null, null, null, venueId, DATE, startsAt, null, locked);
    }

    @Test
    @DisplayName("F1 이름은 NAVER > KOPIS. 입력 순서와 무관하다")
    void name_priority_is_order_independent() {
        Map<Provider, SourceSnapshot> kopisFirst = new LinkedHashMap<>();
        kopisFirst.put(Provider.KOPIS, snapshot("KOPIS 이름", null, null));
        kopisFirst.put(Provider.NAVER, snapshot("네이버 이름", null, null));
        Map<Provider, SourceSnapshot> naverFirst = new LinkedHashMap<>();
        naverFirst.put(Provider.NAVER, snapshot("네이버 이름", null, null));
        naverFirst.put(Provider.KOPIS, snapshot("KOPIS 이름", null, null));

        Fold a = EventSourcePolicy.fold(current("옛 이름", null, null, Set.of()), kopisFirst, Map.of(), List.of());
        Fold b = EventSourcePolicy.fold(current("옛 이름", null, null, Set.of()), naverFirst, Map.of(), List.of());

        assertThat(a.updates()).containsEntry(EventLockableField.NAME, "네이버 이름");
        assertThat(b.updates()).isEqualTo(a.updates());
        assertThat(a.proposals()).isEmpty();
    }

    @Test
    @DisplayName("F2 잠긴 필드는 건드리지 않는다")
    void locked_field_is_skipped() {
        Fold fold = EventSourcePolicy.fold(current("관리자 이름", null, null, Set.of(EventLockableField.NAME)),
                Map.of(Provider.NAVER, snapshot("네이버 이름", null, null)), Map.of(), List.of());

        assertThat(fold.updates()).doesNotContainKey(EventLockableField.NAME);
    }

    @Test
    @DisplayName("F3 시작 시각은 TICKETMASTER > KOPIS. 파싱 실패(null)면 다음 제공자, 둘 다 없으면 없음")
    void starts_at_priority_and_null() {
        LocalDateTime tm = DATE.atTime(19, 0);
        LocalDateTime kopis = DATE.atTime(18, 0);
        Map<Provider, SourceSnapshot> both = Map.of(Provider.TICKETMASTER, snapshot("a", tm, null), Provider.KOPIS, snapshot("b", kopis, null));
        assertThat(EventSourcePolicy.fold(current("n", null, null, Set.of()), both, Map.of(), List.of()).updates())
                .containsEntry(EventLockableField.STARTS_AT, tm);

        Map<Provider, SourceSnapshot> tmNull = Map.of(Provider.TICKETMASTER, snapshot("a", null, null), Provider.KOPIS, snapshot("b", kopis, null));
        assertThat(EventSourcePolicy.fold(current("n", null, null, Set.of()), tmNull, Map.of(), List.of()).updates())
                .containsEntry(EventLockableField.STARTS_AT, kopis);

        Map<Provider, SourceSnapshot> none = Map.of(Provider.TICKETMASTER, snapshot("a", null, null), Provider.KOPIS, snapshot("b", null, null));
        assertThat(EventSourcePolicy.fold(current("n", null, null, Set.of()), none, Map.of(), List.of()).updates())
                .doesNotContainKey(EventLockableField.STARTS_AT);
    }

    @Test
    @DisplayName("민감 필드: null -> 값은 즉시, 값 -> 다른 값은 제안. 같은 값이면 아무것도 없음")
    void sensitive_field_null_to_value_vs_value_to_value() {
        LocalDateTime tm = DATE.atTime(20, 0);
        Map<Provider, SourceSnapshot> sources = Map.of(Provider.TICKETMASTER, snapshot("a", tm, null));

        Fold filled = EventSourcePolicy.fold(current("n", null, null, Set.of()), sources, Map.of(), List.of());
        assertThat(filled.updates()).containsEntry(EventLockableField.STARTS_AT, tm);
        assertThat(filled.proposals()).isEmpty();

        Fold changed = EventSourcePolicy.fold(current("n", DATE.atTime(19, 0), null, Set.of()), sources, Map.of(), List.of());
        assertThat(changed.updates()).doesNotContainKey(EventLockableField.STARTS_AT);
        assertThat(changed.proposals()).containsExactly(
                new Proposal(SourceChangeField.STARTS_AT, "2026-10-23T19:00", "2026-10-23T20:00", Provider.TICKETMASTER));

        Fold same = EventSourcePolicy.fold(current("a", tm, null, Set.of()), sources, Map.of(), List.of());
        assertThat(same.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("F4 거절된 제안과 같은 값은 다시 제안하지 않는다")
    void rejected_proposal_is_suppressed() {
        Map<Provider, SourceSnapshot> sources = Map.of(Provider.TICKETMASTER, snapshot("a", DATE.atTime(20, 0), null));
        Fold fold = EventSourcePolicy.fold(current("n", DATE.atTime(19, 0), null, Set.of()), sources, Map.of(),
                List.of(new Rejected(SourceChangeField.STARTS_AT, "2026-10-23T20:00")));

        assertThat(fold.proposals()).isEmpty();

        // 다른 값이면 다시 제안된다
        Fold other = EventSourcePolicy.fold(current("n", DATE.atTime(19, 0), null, Set.of()),
                Map.of(Provider.TICKETMASTER, snapshot("a", DATE.atTime(21, 0), null)), Map.of(),
                List.of(new Rejected(SourceChangeField.STARTS_AT, "2026-10-23T20:00")));
        assertThat(other.proposals()).hasSize(1);
    }

    @Test
    @DisplayName("행사 날짜와 다른 날짜의 시작 시각은 접지 않는다 (불변식). 날짜 차이는 EVENT_DATE 제안으로 간다")
    void starts_at_on_other_date_is_deferred_to_event_date_proposal() {
        SourceSnapshot moved = new SourceSnapshot("x", "2026-10-24", "a", null, null, null, null,
                LocalDate.of(2026, 10, 24).atTime(19, 0), LocalDate.of(2026, 10, 24), null);
        Fold fold = EventSourcePolicy.fold(current("n", null, null, Set.of()), Map.of(Provider.KOPIS, moved), Map.of(), List.of());

        assertThat(fold.updates()).doesNotContainKey(EventLockableField.STARTS_AT);
        assertThat(fold.proposals()).containsExactly(
                new Proposal(SourceChangeField.EVENT_DATE, "2026-10-23", "2026-10-24", Provider.KOPIS));
    }

    @Test
    @DisplayName("공연장은 KOPIS > TICKETMASTER 의 해석된 venueId 로 접는다")
    void venue_fold() {
        Map<Provider, SourceSnapshot> sources = Map.of(Provider.KOPIS, snapshot("a", null, null), Provider.TICKETMASTER, snapshot("b", null, null));
        Map<Provider, Long> venueIds = Map.of(Provider.KOPIS, 5L, Provider.TICKETMASTER, 6L);

        assertThat(EventSourcePolicy.fold(current("n", null, null, Set.of()), sources, venueIds, List.of()).updates())
                .containsEntry(EventLockableField.VENUE, 5L);
        assertThat(EventSourcePolicy.fold(current("n", null, 4L, Set.of()), sources, venueIds, List.of()).proposals())
                .containsExactly(new Proposal(SourceChangeField.VENUE, "4", "5", Provider.KOPIS));
        // KOPIS 가 공연장을 못 주면 TICKETMASTER
        assertThat(EventSourcePolicy.fold(current("n", null, null, Set.of()), sources, Map.of(Provider.TICKETMASTER, 6L), List.of()).updates())
                .containsEntry(EventLockableField.VENUE, 6L);
    }

    @Test
    @DisplayName("ends_at 은 제안하지 않고 starts_at_local + running_minutes 초안만 돌려준다")
    void ends_at_is_draft_only() {
        Fold fold = EventSourcePolicy.fold(current("n", null, null, Set.of()),
                Map.of(Provider.KOPIS, snapshot("a", DATE.atTime(19, 0), 120)), Map.of(), List.of());

        assertThat(fold.updates()).containsEntry(EventLockableField.RUNNING_MINUTES, 120)
                .doesNotContainKey(EventLockableField.ENDS_AT);
        assertThat(fold.endsAtDraft()).isEqualTo(DATE.atTime(21, 0));
    }

    @Test
    @DisplayName("날짜 이동 판정: 사라진 1 + 나타난 1 만 이동. 나타난 키가 둘이면 발견")
    void occurrence_diff() {
        OccurrenceDiff move = EventSourcePolicy.diffOccurrences(Set.of("2026-10-23"), Set.of("2026-10-24"));
        assertThat(move.isMove()).isTrue();
        assertThat(move.movedFrom()).isEqualTo("2026-10-23");
        assertThat(move.movedTo()).isEqualTo("2026-10-24");

        OccurrenceDiff two = EventSourcePolicy.diffOccurrences(Set.of("2026-10-23"), Set.of("2026-10-24", "2026-10-25"));
        assertThat(two.isMove()).isFalse();
        assertThat(two.vanished()).containsExactly("2026-10-23");
        assertThat(two.appeared()).containsExactlyInAnyOrder("2026-10-24", "2026-10-25");

        OccurrenceDiff same = EventSourcePolicy.diffOccurrences(Set.of("2026-10-23"), Set.of("2026-10-23"));
        assertThat(same.vanished()).isEmpty();
        assertThat(same.appeared()).isEmpty();
    }
}
