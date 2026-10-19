package com.stagelog.event.domain;

/**
 * 행사 생명주기. "정보 부족/완료" 같은 완성도는 여기 두지 않고 응답에서 합성한다.
 */
public enum EventStatus {
    ACTIVE, CANCEL_SUSPECTED, CANCELLED
}
