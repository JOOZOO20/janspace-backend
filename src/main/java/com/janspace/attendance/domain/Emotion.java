package com.janspace.attendance.domain;

import java.util.Optional;

/**
 * 기록 감정 12개. 코드는 언어와 무관하게 고정하고 표시만 언어별로 고른다.
 */
public enum Emotion {
    FLUTTER, OVERWHELMED, LONGING, HAPPY, EXCITED, WISTFUL, PASSION, COMFORT, REGRET, ANGER, LONELY, SAD;

    // 알 수 없는 코드는 empty (호출자가 INVALID_EMOTIONS 로 바꾼다)
    public static Optional<Emotion> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
