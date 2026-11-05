package com.stagelog.ops.domain;

/**
 * 이전 S3 key 를 삭제 예약한 이유. REPLACED = 이미지 교체로 빠진 key, MIGRATED = 고정 key 를 버전 key 로 옮긴 백필.
 */
public enum S3DeleteReason {
    REPLACED,
    MIGRATED
}
