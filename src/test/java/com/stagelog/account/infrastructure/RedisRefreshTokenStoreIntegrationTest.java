package com.stagelog.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

import com.stagelog.account.application.IssuedTokens;
import com.stagelog.account.application.SocialLoginService;
import com.stagelog.account.application.port.RefreshTokenStore.Entry;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.UnauthorizedException;
import com.stagelog.support.IntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class RedisRefreshTokenStoreIntegrationTest extends IntegrationTest {

    @MockitoSpyBean
    RedisRefreshTokenStore store;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    SocialLoginService login;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("회전된 토큰은 가족 집합에서 빠져 집합에는 쓰지 않은 토큰만 남고, 가족 폐기는 남은 토큰과 가족 키를 지운다")
    void rotation_keeps_family_set_small_and_revoke_clears_it() {
        String family = UUID.randomUUID().toString();
        Entry entry = new Entry(1L, family, Instant.now().plus(1, ChronoUnit.DAYS), false);
        String familyKey = RedisRefreshTokenStore.FAMILY_PREFIX + family;

        // t1 -> t2 -> t3 로 두 번 회전
        store.save("t1", entry);
        assertThat(store.rotate("t1", entry, "t2", entry.expiresAt())).isTrue();
        assertThat(store.rotate("t2", entry, "t3", entry.expiresAt())).isTrue();

        assertThat(redis.opsForSet().members(familyKey)).containsExactly("t3");
        // 회전된 토큰은 used 로 남아 재사용 감지에 쓰인다
        assertThat(store.find("t1")).get().extracting(Entry::used).isEqualTo(true);
        assertThat(redis.getExpire(RedisRefreshTokenStore.TOKEN_PREFIX + "t1")).isBetween(1L, 600L);

        store.revokeFamily(family);

        assertThat(store.find("t3")).isEmpty();
        assertThat(redis.hasKey(familyKey)).isFalse();
    }

    @Test
    @DisplayName("같은 토큰의 동시 회전은 한 번만 성공하고 재사용 요청이 후속 토큰까지 폐기")
    void concurrent_rotation_consumes_once_and_revokes_successor() throws Exception {
        String token = UUID.randomUUID().toString();
        String firstToken = UUID.randomUUID().toString();
        String secondToken = UUID.randomUUID().toString();
        Entry entry = new Entry(2L, UUID.randomUUID().toString(), Instant.now().plusSeconds(3600), false);
        store.save(token, entry);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() -> { await(start); return store.rotate(token, entry, firstToken, entry.expiresAt()); });
            Future<Boolean> second = pool.submit(() -> { await(start); return store.rotate(token, entry, secondToken, entry.expiresAt()); });
            start.countDown();
            assertThat((first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
        assertThat(store.find(firstToken)).isEmpty();
        assertThat(store.find(secondToken)).isEmpty();
        assertThat(redis.hasKey(RedisRefreshTokenStore.FAMILY_PREFIX + entry.familyId())).isFalse();
    }

    @Test
    @DisplayName("폐기 전에 읽어 둔 토큰으로 회전해도 로그아웃한 가족이 다시 생성되지 않음")
    void revoked_family_cannot_be_resurrected_by_stale_rotation() {
        String token = UUID.randomUUID().toString();
        String nextToken = UUID.randomUUID().toString();
        Entry entry = new Entry(3L, UUID.randomUUID().toString(), Instant.now().plusSeconds(3600), false);
        store.save(token, entry);
        Entry snapshot = store.find(token).orElseThrow();
        store.revokeFamily(entry.familyId());

        assertThat(store.rotate(token, snapshot, nextToken, entry.expiresAt())).isFalse();
        assertThat(store.find(nextToken)).isEmpty();
        assertThat(redis.hasKey(RedisRefreshTokenStore.FAMILY_PREFIX + entry.familyId())).isFalse();
    }

    @Test
    @DisplayName("사용자 전체 폐기는 회전된 후속 토큰을 포함하며 다른 사용자의 가족은 유지")
    void revoke_all_clears_rotated_sessions_only_for_target_user() {
        Entry first = new Entry(4L, UUID.randomUUID().toString(), Instant.now().plusSeconds(3600), false);
        Entry second = new Entry(4L, UUID.randomUUID().toString(), first.expiresAt(), false);
        Entry other = new Entry(5L, UUID.randomUUID().toString(), first.expiresAt(), false);
        String token = UUID.randomUUID().toString();
        String successor = UUID.randomUUID().toString();
        String anotherSession = UUID.randomUUID().toString();
        String otherUser = UUID.randomUUID().toString();
        store.save(token, first);
        store.save(anotherSession, second);
        store.save(otherUser, other);
        assertThat(store.rotate(token, first, successor, first.expiresAt())).isTrue();

        store.revokeAllOf(4L);

        assertThat(store.find(successor)).isEmpty();
        assertThat(store.find(anotherSession)).isEmpty();
        assertThat(store.find(otherUser)).isPresent();
        assertThat(redis.hasKey(RedisRefreshTokenStore.USER_PREFIX + 4L)).isFalse();
    }

    @Test
    @DisplayName("서비스에서 두 요청이 같은 미사용 토큰을 읽어도 토큰 발급은 한 번만 성공")
    void service_does_not_issue_two_tokens_from_same_snapshot() throws Exception {
        String nickname = "refresh-race-" + UUID.randomUUID();
        jdbc.update("INSERT INTO users (nickname, locale, is_admin, created_at, updated_at) VALUES (?, 'ko', FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                nickname);
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, nickname);
        String token = UUID.randomUUID().toString();
        Entry entry = new Entry(userId, UUID.randomUUID().toString(), Instant.now().plusSeconds(3600), false);
        store.save(token, entry);
        CountDownLatch read = new CountDownLatch(2);
        doAnswer(call -> {
            Object snapshot = call.callRealMethod();
            read.countDown();
            await(read);
            return snapshot;
        }).when(store).find(token);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<IssuedTokens> first = pool.submit(() -> refreshOrReject(token));
            Future<IssuedTokens> second = pool.submit(() -> refreshOrReject(token));
            IssuedTokens a = first.get(10, TimeUnit.SECONDS);
            IssuedTokens b = second.get(10, TimeUnit.SECONDS);
            assertThat((a == null ? 0 : 1) + (b == null ? 0 : 1)).isEqualTo(1);
            assertThat(store.find((a != null ? a : b).refreshToken())).isEmpty();
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    private IssuedTokens refreshOrReject(String token) {
        try {
            return login.refresh(token);
        } catch (UnauthorizedException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
            return null;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 회전 동기화 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
