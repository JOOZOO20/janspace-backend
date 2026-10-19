package com.stagelog.setlist.infrastructure;

import com.stagelog.setlist.application.port.PlaylistSyncLock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 재생목록 동기화 잠금. SET key token NX PX 로 잡고, 토큰이 같을 때만 지우는 Lua 로 푼다.
 */
@Component
@RequiredArgsConstructor
public class RedisPlaylistSyncLock implements PlaylistSyncLock {

    static final String PREFIX = "setlist:playlist-sync:";
    // ponytail: 고정 TTL, 연장 없음. 동기화가 10분을 넘기면(항목 수백 개 + 유튜브 지연) 잠금이 풀려 겹칠 수 있다. 그때 갱신 스레드를 둔다
    static final Duration TTL = Duration.ofMinutes(10);
    private static final RedisScript<Long> RELEASE = RedisScript.of(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;

    @Override
    public Optional<String> tryAcquire(Long eventId) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + eventId, token, TTL)) ? Optional.of(token) : Optional.empty();
    }

    @Override
    public void release(Long eventId, String token) {
        redis.execute(RELEASE, List.of(PREFIX + eventId), token);
    }
}
