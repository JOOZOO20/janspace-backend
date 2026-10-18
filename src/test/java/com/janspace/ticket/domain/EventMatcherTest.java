package com.janspace.ticket.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.ticket.domain.EventMatcher.Scored;
import com.janspace.ticket.domain.EventMatcher.Target;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventMatcherTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    static final LocalDate DAY = LocalDate.of(2026, 9, 16);

    static Target target(long id, LocalDate date, LocalTime start, String... labels) {
        return new Target(id, date, start, List.of(labels));
    }

    static List<Long> order(List<Scored> ranked) {
        return ranked.stream().map(s -> s.target().eventId()).toList();
    }

    @Test
    @DisplayName("같은 날 두 행사: 공연장 이름이 글자에 있는 쪽이 먼저 (공백·대소문자 무시)")
    void venue_decides() {
        Target festival = target(1, DAY, LocalTime.of(15, 0), "그랜드 민트 페스티벌 2026", "올림픽공원 88잔디마당");
        Target concert = target(2, DAY, LocalTime.of(19, 0), "잔나비 전국투어 - 서울", "KSPO DOME");
        List<Scored> ranked = EventMatcher.rank(TicketTextTest.INTERPARK, List.of(festival, concert), TODAY);

        assertThat(order(ranked)).containsExactly(2L, 1L);
        // 이름 전체 일치 80 + 오후 7시 = 19:00 일치 20
        assertThat(ranked.get(0).score()).isEqualTo(100);
        assertThat(ranked.get(1).score()).isLessThan(50);
    }

    @Test
    @DisplayName("번역 이름으로도 맞춘다: 글자가 'KSPO돔' 이면 번역 'KSPO 돔' 과 일치")
    void translated_label() {
        Target withKo = target(2, DAY, null, "JANNABI LIVE", "KSPO DOME", "KSPO 돔");
        Target other = target(3, DAY, null, "JANNABI LIVE", "Blue Square");
        List<Scored> ranked = EventMatcher.rank(TicketTextTest.YES24, List.of(other, withKo), TODAY);
        assertThat(order(ranked)).containsExactly(2L, 3L);
        assertThat(ranked.get(0).score()).isEqualTo(80);
    }

    @Test
    @DisplayName("같은 날 두 회차(16:00·19:00): 글자의 시각과 맞는 회차가 먼저")
    void same_day_two_shows() {
        Target matinee = target(10, DAY, LocalTime.of(16, 0), "잔나비 소극장 콘서트", "롤링홀");
        Target evening = target(11, DAY, LocalTime.of(20, 0), "잔나비 소극장 콘서트", "롤링홀");
        List<Scored> ranked = EventMatcher.rank(TicketTextTest.STANDING, List.of(matinee, evening), TODAY);
        assertThat(order(ranked)).containsExactly(11L, 10L);
        assertThat(ranked.get(0).score() - ranked.get(1).score()).isEqualTo(EventMatcher.TIME_WEIGHT);
    }

    @Test
    @DisplayName("OCR 이 공백을 빼먹거나 일부만 읽어도 부분 점수")
    void partial_and_spacing() {
        String text = EventMatcher.compact("올림픽공원KSPODOME");
        assertThat(EventMatcher.labelScore(text, "KSPO DOME")).isEqualTo(1.0);
        assertThat(EventMatcher.labelScore(EventMatcher.compact("Ｔｈｅ Ｓｔａｒ Ｔｈｅａｔｒｅ"), "the star theatre")).isEqualTo(1.0);
        // "Zepp" 만 읽힘: Zepp(4) / Zepp+Haneda(10)
        assertThat(EventMatcher.labelScore(EventMatcher.compact("Zepp 東京"), "Zepp Haneda")).isEqualTo(0.4);
        // 짧은 라틴 토큰(of, in)은 우연히 맞지 않게 버린다
        assertThat(EventMatcher.tokens("Hall of Fame in Seoul")).containsExactly("hall", "fame", "seoul");
        assertThat(EventMatcher.labelScore(text, "")).isZero();
    }

    @Test
    @DisplayName("점수가 같으면 오늘에 가까운 날짜, 그다음 행사 ID")
    void tie_breaks() {
        Target far = target(5, LocalDate.of(2025, 9, 16), null, "잔나비");
        Target near = target(6, DAY, null, "잔나비");
        Target nearOther = target(4, DAY, null, "잔나비");
        assertThat(order(EventMatcher.rank(List.of("아무 글자"), List.of(far, near, nearOther), TODAY))).containsExactly(4L, 6L, 5L);
    }
}
