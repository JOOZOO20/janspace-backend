package com.stagelog.setlist.domain;

/**
 * 원장 행 상태. RESERVED 에서 한 번만 CONSUMED(응답 받음, 실패 응답 포함)·UNKNOWN(결과 모름, 사용으로 계산)·RELEASED(호출 안 함)로 바뀐다.
 */
public enum QuotaEntryStatus {
    RESERVED, CONSUMED, UNKNOWN, RELEASED
}
