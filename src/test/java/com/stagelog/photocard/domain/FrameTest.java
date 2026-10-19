package com.stagelog.photocard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FrameTest {

    static final List<SlotRect> FOUR = List.of(new SlotRect(90, 84, 900, 672), new SlotRect(90, 804, 900, 672),
            new SlotRect(90, 1524, 900, 672), new SlotRect(90, 2244, 900, 672));
    static final LocalDate D = LocalDate.of(2026, 10, 10);

    @Test
    @DisplayName("상시 프레임은 날짜와 상관없이, 스페셜은 시작일~종료일(포함)에만 공개된다")
    void availability() {
        Frame original = new Frame("클린", FrameKind.ORIGINAL, null, null, null, "frames/a.svg", FOUR);
        Frame special = new Frame("10월", FrameKind.SPECIAL, "2026 가을", D, D.plusDays(29), "frames/b.svg", FOUR);

        assertThat(original.isAvailableOn(D.minusYears(1))).isTrue();
        assertThat(special.isAvailableOn(D.minusDays(1))).isFalse();
        assertThat(special.isAvailableOn(D)).isTrue();
        assertThat(special.isAvailableOn(D.plusDays(29))).isTrue();
        assertThat(special.isAvailableOn(D.plusDays(30))).isFalse();
    }

    @Test
    @DisplayName("상시에 기간을 넣거나, 스페셜에 기간이 없거나 거꾸로면 400 INVALID_INPUT")
    void period_rules() {
        assertInvalid(() -> new Frame("x", FrameKind.ORIGINAL, null, D, null, "k", FOUR));
        assertInvalid(() -> new Frame("x", FrameKind.ORIGINAL, null, null, D, "k", FOUR));
        assertInvalid(() -> new Frame("x", FrameKind.SPECIAL, null, D, null, "k", FOUR));
        assertInvalid(() -> new Frame("x", FrameKind.SPECIAL, null, null, D, "k", FOUR));
        assertInvalid(() -> new Frame("x", FrameKind.SPECIAL, null, D, D.minusDays(1), "k", FOUR));
        assertThat(new Frame("x", FrameKind.SPECIAL, null, D, D, "k", FOUR).isAvailableOn(D)).isTrue();
    }

    @Test
    @DisplayName("사진 칸은 3~4개, 1080×3240 안의 양수 크기만 받는다 (경계는 캔버스 끝까지)")
    void slot_rules() {
        Frame.validateSlots(FOUR);
        Frame.validateSlots(FOUR.subList(0, 3));
        Frame.validateSlots(List.of(new SlotRect(0, 0, 1080, 3240), new SlotRect(0, 0, 1, 1), new SlotRect(1079, 3239, 1, 1)));
        assertInvalid(() -> Frame.validateSlots(null));
        assertInvalid(() -> Frame.validateSlots(FOUR.subList(0, 2)));
        assertInvalid(() -> Frame.validateSlots(List.of(FOUR.get(0), FOUR.get(1), FOUR.get(2), FOUR.get(3), FOUR.get(0))));
        assertInvalid(() -> Frame.validateSlots(List.of(FOUR.get(0), FOUR.get(1), new SlotRect(-1, 0, 10, 10))));
        assertInvalid(() -> Frame.validateSlots(List.of(FOUR.get(0), FOUR.get(1), new SlotRect(0, 0, 0, 10))));
        assertInvalid(() -> Frame.validateSlots(List.of(FOUR.get(0), FOUR.get(1), new SlotRect(100, 0, 981, 10))));
        assertInvalid(() -> Frame.validateSlots(List.of(FOUR.get(0), FOUR.get(1), new SlotRect(0, 3000, 10, 241))));
    }

    static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BadRequestException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
