package com.stagelog.account.infrastructure;

import com.stagelog.account.application.port.RefreshTokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 리프레시 토큰 = 키 auth:refresh:{token}, 가족 색인 = auth:family:{familyId} (토큰 집합),
 * 사용자 색인 = auth:user-families:{userId} (가족 집합). 회전된 토큰은 used 로 다시 써서 짧게 남기고 가족 집합에서 뺀다.
 * 그래서 가족 집합에는 아직 쓰지 않은 토큰(보통 1개)만 남고, 회전할수록 집합이 커지지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {

    static final String TOKEN_PREFIX = "auth:refresh:";
    static final String FAMILY_PREFIX = "auth:family:";
    static final String USER_PREFIX = "auth:user-families:";
    // 재사용 감지 창. 이 시간이 지나면 재사용은 그냥 "없는 토큰" 401 이 된다
    static final Duration USED_TTL = Duration.ofMinutes(10);

    private static final RedisScript<Long> SAVE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('SADD', KEYS[2], ARGV[3])
            if redis.call('PTTL', KEYS[2]) < tonumber(ARGV[2]) then redis.call('PEXPIRE', KEYS[2], ARGV[2]) end
            redis.call('SADD', KEYS[3], ARGV[4])
            if redis.call('PTTL', KEYS[3]) < tonumber(ARGV[2]) then redis.call('PEXPIRE', KEYS[3], ARGV[2]) end
            return 1
            """, Long.class);

    private static final RedisScript<Long> ROTATE = RedisScript.of("""
            local current = redis.call('GET', KEYS[1])
            if not current then return 0 end
            if current ~= ARGV[1] then
                if cjson.decode(current).used then
                    for _, token in ipairs(redis.call('SMEMBERS', KEYS[2])) do
                        redis.call('DEL', ARGV[9] .. token)
                    end
                    redis.call('DEL', KEYS[2])
                end
                return 0
            end
            if redis.call('SISMEMBER', KEYS[2], ARGV[6]) == 0 then return 0 end
            if redis.call('EXISTS', KEYS[3]) == 1 then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[4])
            redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[5])
            redis.call('SREM', KEYS[2], ARGV[6])
            redis.call('SADD', KEYS[2], ARGV[7])
            redis.call('PEXPIRE', KEYS[2], ARGV[5])
            redis.call('SADD', KEYS[4], ARGV[8])
            if redis.call('PTTL', KEYS[4]) < tonumber(ARGV[5]) then redis.call('PEXPIRE', KEYS[4], ARGV[5]) end
            return 1
            """, Long.class);

    private static final RedisScript<Long> REVOKE_FAMILY = RedisScript.of("""
            for _, token in ipairs(redis.call('SMEMBERS', KEYS[1])) do
                redis.call('DEL', ARGV[1] .. token)
            end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private static final RedisScript<Long> REVOKE_ALL = RedisScript.of("""
            for _, family in ipairs(redis.call('SMEMBERS', KEYS[1])) do
                local familyKey = ARGV[1] .. family
                for _, token in ipairs(redis.call('SMEMBERS', familyKey)) do
                    redis.call('DEL', ARGV[2] .. token)
                end
                redis.call('DEL', familyKey)
            end
            return redis.call('DEL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public void save(String token, Entry entry) {
        long ttl = Duration.between(Instant.now(), entry.expiresAt()).toMillis();
        if (ttl <= 0 || !Long.valueOf(1).equals(redis.execute(SAVE,
                List.of(TOKEN_PREFIX + token, FAMILY_PREFIX + entry.familyId(), USER_PREFIX + entry.userId()),
                objectMapper.writeValueAsString(entry), Long.toString(ttl), token, entry.familyId()))) {
            throw new IllegalStateException("리프레시 토큰을 저장할 수 없습니다.");
        }
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
    public boolean rotate(String token, Entry expected, String nextToken, Instant expiresAt) {
        long ttl = Duration.between(Instant.now(), expiresAt).toMillis();
        if (expected.used() || ttl <= 0) {
            return false;
        }
        if (token.equals(nextToken)) {
            throw new IllegalArgumentException("회전 전후 토큰은 달라야 합니다.");
        }
        return Long.valueOf(1).equals(redis.execute(ROTATE,
                List.of(TOKEN_PREFIX + token, FAMILY_PREFIX + expected.familyId(), TOKEN_PREFIX + nextToken,
                        USER_PREFIX + expected.userId()),
                objectMapper.writeValueAsString(expected), objectMapper.writeValueAsString(expected.asUsed()),
                objectMapper.writeValueAsString(expected.rotated(expiresAt)), Long.toString(USED_TTL.toMillis()),
                Long.toString(ttl), token, nextToken, expected.familyId(), TOKEN_PREFIX));
    }

    @Override
    public void revokeFamily(String familyId) {
        // 토큰 목록 조회와 삭제도 한 연산이어야 회전 직후 생긴 토큰이 폐기에서 빠지지 않는다.
        redis.execute(REVOKE_FAMILY, List.of(FAMILY_PREFIX + familyId), TOKEN_PREFIX);
    }

    @Override
    public void revokeAllOf(Long userId) {
        redis.execute(REVOKE_ALL, List.of(USER_PREFIX + userId), FAMILY_PREFIX, TOKEN_PREFIX);
    }
}
