package com.janspace.ops.domain;

/**
 * 동기화 출처. event_sources.provider 와 같은 값을 쓰고, 재생목록 동기화만 YOUTUBE 를 추가한다.
 */
public enum SyncSource {
    NAVER, KOPIS, TICKETMASTER, SETLISTFM, YOUTUBE
}
