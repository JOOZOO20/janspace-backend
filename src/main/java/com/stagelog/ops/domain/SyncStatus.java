package com.stagelog.ops.domain;

/**
 * 동기화 실행 상태. 시작 시 RUNNING, 종료 시 OK 또는 FAILED.
 */
public enum SyncStatus {
    RUNNING, OK, FAILED
}
