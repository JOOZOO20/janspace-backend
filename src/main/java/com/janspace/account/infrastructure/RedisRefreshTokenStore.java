package com.janspace.account.infrastructure;

import com.janspace.account.application.port.RefreshTokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 리프레시 토큰 = 키 auth:refresh:{token}, 가족 색인 = auth:family:{familyId} (토큰 집합),
 * 사용자 색인 = auth:user-families:{userId} (가족 집합). 회전된 토큰은 used 로 다시 써서 짧게 남긴다.
 */
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    static final String TOKEN_PREFIX = "auth:refresh:";
    static final String FAMILY_PREFIX = "auth:family:";
    static final String USER_PREFIX = "auth:user-families:";
    // 재사용 감지 창. 이 시간이 지나면 재사용은 그냥 "없는 토큰" 401 이 된다
    static final Duration USED_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public void save(String token, Entry entry) {
        Duration ttl = Duration.between(Instant.now(), entry.expiresAt());
        redis.opsForValue().set(TOKEN_PREFIX + token, objectMapper.writeValueAsString(entry), ttl);
        redis.opsForSet().add(FAMILY_PREFIX + entry.familyId(), token);
        redis.expire(FAMILY_PREFIX + entry.familyId(), ttl);
        redis.opsForSet().add(USER_PREFIX + entry.userId(), entry.familyId());
        redis.expire(USER_PREFIX + entry.userId(), ttl);
    }

    @Override
    public Optional<Entry> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(TOKEN_PREFIX + token);
        return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, Entry.class));
    }

    @Override
    public void markUsed(String token, Entry entry) {
        redis.opsForValue().set(TOKEN_PREFIX + token, objectMapper.writeValueAsString(entry.asUsed()), USED_TTL);
    }

    @Override
    public void revokeFamily(String familyId) {
        Set<String> tokens = redis.opsForSet().members(FAMILY_PREFIX + familyId);
        if (tokens != null) {
            tokens.forEach(t -> redis.delete(TOKEN_PREFIX + t));
        }
        redis.delete(FAMILY_PREFIX + familyId);
    }

    @Override
    public void revokeAllOf(Long userId) {
        Set<String> families = redis.opsForSet().members(USER_PREFIX + userId);
        if (families != null) {
            families.forEach(this::revokeFamily);
        }
        redis.delete(USER_PREFIX + userId);
    }
}
