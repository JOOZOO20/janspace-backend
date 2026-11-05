package com.stagelog.ops.domain;

/**
 * 동기화 실행 상태. 시작 시 RUNNING, 종료 시 OK·FAILED·PARTIAL(일부만 반영)·DEFERRED(Quota 부족으로 시작하지 않고 미룸).
 */
public enum SyncStatus {
    RUNNING, OK, FAILED, PARTIAL, DEFERRED
}
