package com.stagelog.setlist.infrastructure;

import com.stagelog.setlist.application.port.OAuthStateStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 유튜브 OAuth state (TTL 10분). consume 은 GETDEL 이라 한 번만 성공한다.
 */
@Component
@RequiredArgsConstructor
public class RedisOAuthStateStore implements OAuthStateStore {

    static final String PREFIX = "youtube:oauth:state:";
    static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    @Override
    public void save(String state) {
        redis.opsForValue().set(PREFIX + state, "1", TTL);
    }

    @Override
    public boolean consume(String state) {
        return state != null && !state.isBlank() && redis.opsForValue().getAndDelete(PREFIX + state) != null;
    }
}
