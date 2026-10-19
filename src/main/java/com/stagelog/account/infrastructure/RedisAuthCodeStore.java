package com.stagelog.account.infrastructure;

import com.stagelog.account.application.port.AuthCodeStore;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * state·인증 코드·연결 코드를 JSON 문자열로 저장한다. consume 은 GETDEL 이라 두 요청이 같은 코드를 쓸 수 없다.
 */
@Component
@RequiredArgsConstructor
public class RedisAuthCodeStore implements AuthCodeStore {

    static final String STATE_PREFIX = "auth:state:";
    static final String CODE_PREFIX = "auth:code:";
    static final String LINK_PREFIX = "auth:link:";
    static final Duration STATE_TTL = Duration.ofMinutes(10);
    static final Duration CODE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public void saveState(String state, LoginState loginState) {
        put(STATE_PREFIX + state, loginState, STATE_TTL);
    }

    @Override
    public Optional<LoginState> consumeState(String state) {
        return take(STATE_PREFIX, state, LoginState.class);
    }

    @Override
    public void saveAuthCode(String code, AuthCode authCode) {
        put(CODE_PREFIX + code, authCode, CODE_TTL);
    }

    @Override
    public Optional<AuthCode> consumeAuthCode(String code) {
        return take(CODE_PREFIX, code, AuthCode.class);
    }

    @Override
    public void saveLinkCode(String code, LinkCode linkCode) {
        put(LINK_PREFIX + code, linkCode, CODE_TTL);
    }

    @Override
    public Optional<LinkCode> consumeLinkCode(String code) {
        return take(LINK_PREFIX, code, LinkCode.class);
    }

    private void put(String key, Object value, Duration ttl) {
        redis.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
    }

    // GETDEL: 읽는 순간 지워지므로 같은 코드로 두 번 성공할 수 없다
    private <T> Optional<T> take(String prefix, String id, Class<T> type) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String json = redis.opsForValue().getAndDelete(prefix + id);
        return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, type));
    }
}
