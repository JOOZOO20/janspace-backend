package com.janspace.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.locale.SupportedLocale;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventTest {

    static final LocalDate DATE = LocalDate.of(2026, 10, 23);

    static Event event() {
        return Event.create("잔나비 단독 공연", SupportedLocale.KO, EventType.CONCERT, DATE);
    }

    @Test
    @DisplayName("recomputeInstants: 방콕 21:00 현지 = 14:00Z. 시간대가 바뀌면 UTC 가 옮겨진다")
    void recompute_instants_from_local_and_timezone() {
        Event e = event();
        e.applySource(EventLockableField.TIMEZONE, "Asia/Bangkok");
        e.applySource(EventLockableField.STARTS_AT, DATE.atTime(21, 0));

        assertThat(e.getStartsAt()).isEqualTo(Instant.parse("2026-10-23T14:00:00Z"));
        assertThat(e.getEndsAt()).isNull();

        e.applySource(EventLockableField.TIMEZONE, "Asia/Seoul");
        assertThat(e.getStartsAt()).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
        assertThat(e.getStartsAtLocal()).isEqualTo(DATE.atTime(21, 0));

        assertThat(e.recomputeInstants()).as("같은 입력이면 변화 없음").isFalse();
    }

    @Test
    @DisplayName("불변식: 시작 시각의 날짜는 행사 날짜와 같아야 하고, 날짜 이동은 시각을 보존한다")
    void invariant_and_date_move() {
        Event e = event();
        assertThatThrownBy(() -> e.applySource(EventLockableField.STARTS_AT, DATE.plusDays(1).atTime(21, 0)))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_EVENT_SCHEDULE);

        e.applySource(EventLockableField.STARTS_AT, DATE.atTime(19, 0));
        e.applySource(EventLockableField.ENDS_AT, DATE.atTime(21, 30));
        e.applySource(EventLockableField.EVENT_DATE, DATE.plusDays(2));

        assertThat(e.getEventDate()).isEqualTo(DATE.plusDays(2));
        assertThat(e.getStartsAtLocal()).isEqualTo(DATE.plusDays(2).atTime(19, 0));
        assertThat(e.getEndsAtLocal()).isEqualTo(DATE.plusDays(2).atTime(21, 30));
        assertThat(e.getStartsAt()).isEqualTo(Instant.parse("2026-10-25T10:00:00Z"));
    }

    @Test
    @DisplayName("종료가 시작보다 앞서거나 시간대를 모르면 INVALID_EVENT_SCHEDULE")
    void invalid_schedule() {
        Event e = event();
        e.applySource(EventLockableField.STARTS_AT, DATE.atTime(19, 0));
        assertThatThrownBy(() -> e.applySource(EventLockableField.ENDS_AT, DATE.atTime(18, 0)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> e.applySource(EventLockableField.TIMEZONE, "Mars/Olympus"))
                .isInstanceOf(BadRequestException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_EVENT_SCHEDULE);
    }

    @Test
    @DisplayName("adminUpdate 는 값 변경 + 잠금, 잠긴 필드는 applySource 가 무시, unlock 후 다시 접힌다")
    void lock_and_unlock() {
        Event e = event();
        assertThat(e.adminUpdate(EventLockableField.NAME, "관리자 이름")).isTrue();
        assertThat(e.isLocked(EventLockableField.NAME)).isTrue();

        assertThat(e.applySource(EventLockableField.NAME, "출처 이름")).isFalse();
        assertThat(e.getName()).isEqualTo("관리자 이름");

        e.unlock(EventLockableField.NAME);
        assertThat(e.applySource(EventLockableField.NAME, "출처 이름")).isTrue();
        assertThat(e.getName()).isEqualTo("출처 이름");
        assertThat(e.isLocked(EventLockableField.NAME)).isFalse();

        // 같은 값이면 changed=false 지만 잠금은 걸린다
        assertThat(e.adminUpdate(EventLockableField.NAME, "출처 이름")).isFalse();
        assertThat(e.isLocked(EventLockableField.NAME)).isTrue();
    }

    @Test
    @DisplayName("locked_fields JSON 변환은 정렬된 이름 배열이며 왕복된다")
    void locked_fields_converter_round_trip() {
        EventLockedFieldsConverter converter = new EventLockedFieldsConverter();
        String json = converter.convertToDatabaseColumn(java.util.EnumSet.of(EventLockableField.VENUE, EventLockableField.NAME));
        assertThat(json).isEqualTo("[\"NAME\",\"VENUE\"]");
        assertThat(converter.convertToEntityAttribute(json)).containsExactly(EventLockableField.NAME, EventLockableField.VENUE);
        assertThat(converter.convertToEntityAttribute("[]")).isEmpty();
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }
}
