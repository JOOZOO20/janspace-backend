package com.stagelog.notification.domain;

/**
 * 푸시 잡 종류. DB 값은 RECORD/RECORD_FALLBACK/SETLIST_CONFIRMED 이고, 서비스 워커가 읽는 페이로드 kind 는 payloadKind 로 명시 매핑한다.
 */
public enum PushKind {

    RECORD("RECORD_REMINDER", "push.record"),
    RECORD_FALLBACK("RECORD_REMINDER_FALLBACK", "push.record_fallback"),
    SETLIST_CONFIRMED("SETLIST_CONFIRMED", "push.setlist_confirmed");

    private final String payloadKind;
    private final String messageKey;

    PushKind(String payloadKind, String messageKey) {
        this.payloadKind = payloadKind;
        this.messageKey = messageKey;
    }

    public String payloadKind() {
        return payloadKind;
    }

    // MessageSource 키 접두사. ".title" / ".body" 를 붙인다
    public String messageKey() {
        return messageKey;
    }

    // RECORD 와 RECORD_FALLBACK 은 행사당 배타
    public PushKind exclusiveCounterpart() {
        return switch (this) {
            case RECORD -> RECORD_FALLBACK;
            case RECORD_FALLBACK -> RECORD;
            case SETLIST_CONFIRMED -> null;
        };
    }
}
