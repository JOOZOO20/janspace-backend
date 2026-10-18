package com.janspace.event.domain;

/**
 * 관리자가 잠글 수 있는 행사 필드. locked_fields JSON 배열에 이름으로 저장된다.
 */
public enum EventLockableField {
    NAME, VENUE, EVENT_DATE, STARTS_AT, ENDS_AT, RUNNING_MINUTES, TYPE, SEASON, POSTER, TICKET_URL, DESCRIPTION, TIMEZONE
}
