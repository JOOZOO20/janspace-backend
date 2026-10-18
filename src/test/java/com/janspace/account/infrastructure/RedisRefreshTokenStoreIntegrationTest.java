package com.janspace.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.janspace.account.application.port.RefreshTokenStore.Entry;
import com.janspace.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisRefreshTokenStoreIntegrationTest extends IntegrationTest {

    @Autowired
    RedisRefreshTokenStore store;
    @Autowired
    StringRedisTemplate redis;

    @Test
    @DisplayName("회전된 토큰은 가족 집합에서 빠져 집합에는 쓰지 않은 토큰만 남고, 가족 폐기는 남은 토큰과 가족 키를 지운다")
    void rotation_keeps_family_set_small_and_revoke_clears_it() {
        String family = UUID.randomUUID().toString();
        Entry entry = new Entry(1L, family, Instant.now().plus(1, ChronoUnit.DAYS), false);
        String familyKey = RedisRefreshTokenStore.FAMILY_PREFIX + family;

        // t1 -> t2 -> t3 로 두 번 회전
        store.save("t1", entry);
        store.markUsed("t1", entry);
        store.save("t2", entry);
        store.markUsed("t2", entry);
        store.save("t3", entry);

        assertThat(redis.opsForSet().members(familyKey)).containsExactly("t3");
        // 회전된 토큰은 used 로 남아 재사용 감지에 쓰인다
        assertThat(store.find("t1")).get().extracting(Entry::used).isEqualTo(true);

        store.revokeFamily(family);

        assertThat(store.find("t3")).isEmpty();
        assertThat(redis.hasKey(familyKey)).isFalse();
    }
}
