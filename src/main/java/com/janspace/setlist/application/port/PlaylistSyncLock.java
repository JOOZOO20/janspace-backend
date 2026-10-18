package com.janspace.setlist.application.port;

import java.util.Optional;

/**
 * 행사별 재생목록 동기화 잠금 (Redis SET NX PX). 인스턴스가 여러 개여도 한 행사의 동기화는 하나만 돈다.
 */
public interface PlaylistSyncLock {

    // 잡으면 해제용 토큰, 다른 동기화가 잡고 있으면 empty
    Optional<String> tryAcquire(Long eventId);

    // 토큰이 같을 때만 푼다 (TTL 이 지나 남이 잡은 잠금은 건드리지 않는다)
    void release(Long eventId, String token);
}
