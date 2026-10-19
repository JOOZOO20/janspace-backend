package com.stagelog.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stagelog.common.auth.AuthenticatedUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccessTokenServiceTest {

    static final String SECRET = "unit-test-secret-key-0123456789abcdef";
    static final Instant NOW = Instant.parse("2026-09-24T09:00:00Z");

    AccessTokenService at(Instant instant) {
        return new AccessTokenService(SECRET, Duration.ofMinutes(30), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("발급한 토큰을 검증하면 sub 와 adm 이 그대로 나온다")
    void issue_and_verify() {
        AccessTokenService service = at(NOW);
        Optional<AuthenticatedUser> user = service.verify(service.issue(42L, true));
        assertThat(user).contains(new AuthenticatedUser(42L, true));
        assertThat(service.verify(service.issue(7L, false))).contains(new AuthenticatedUser(7L, false));
    }

    @Test
    @DisplayName("만료된 토큰은 empty")
    void expired_token_is_rejected() {
        String token = at(NOW).issue(1L, false);
        assertThat(at(NOW.plus(Duration.ofMinutes(31))).verify(token)).isEmpty();
        assertThat(at(NOW.plus(Duration.ofMinutes(29))).verify(token)).isPresent();
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰과 손상된 토큰은 empty")
    void wrong_key_or_garbage_is_rejected() {
        String other = new AccessTokenService("another-secret-key-0123456789abcdefgh", Duration.ofMinutes(30),
                Clock.fixed(NOW, ZoneOffset.UTC)).issue(1L, false);
        AccessTokenService service = at(NOW);
        assertThat(service.verify(other)).isEmpty();
        assertThat(service.verify("not.a.jwt")).isEmpty();
        assertThat(service.verify("")).isEmpty();
    }

    @Test
    @DisplayName("32바이트 미만 비밀키는 기동을 막는다")
    void short_secret_fails_fast() {
        assertThatThrownBy(() -> new AccessTokenService("short", Duration.ofMinutes(30), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
