package com.stagelog.media.domain;

/**
 * 원본 asset 상태. rendition 이 모두 끝나면 집계한다: 모두 READY 면 READY, 일부만 FAILED 면 PARTIAL, 모두 FAILED 면 FAILED.
 */
public enum AssetStatus {
    PENDING, READY, PARTIAL, FAILED
}
