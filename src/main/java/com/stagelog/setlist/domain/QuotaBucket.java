package com.stagelog.setlist.domain;

/**
 * Quota 예산 구분. AUTO = 셋리 변경 뒤 자동 동기화·DEFERRED 재시도, MANUAL = 관리자 수동 동기화.
 */
public enum QuotaBucket {
    AUTO, MANUAL
}
