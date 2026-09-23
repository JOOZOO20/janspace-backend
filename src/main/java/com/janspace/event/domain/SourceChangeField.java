package com.janspace.event.domain;

/**
 * 관리자 확인 큐를 타는 민감 필드. ends_at 은 출처가 제안하지 않으므로 없다.
 */
public enum SourceChangeField {
    EVENT_DATE, STARTS_AT, VENUE;

    public EventLockableField lockable() {
        return switch (this) {
            case EVENT_DATE -> EventLockableField.EVENT_DATE;
            case STARTS_AT -> EventLockableField.STARTS_AT;
            case VENUE -> EventLockableField.VENUE;
        };
    }
}
