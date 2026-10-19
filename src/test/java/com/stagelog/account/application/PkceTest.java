package com.stagelog.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PkceTest {

    // RFC 7636 부록 B 의 예시 verifier/challenge
    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    @DisplayName("RFC 7636 예시 verifier 의 S256 challenge 가 일치한다")
    void rfc_example_round_trip() {
        assertThat(Pkce.challengeOf(VERIFIER)).isEqualTo(CHALLENGE);
        assertThat(Pkce.matches(VERIFIER, CHALLENGE)).isTrue();
    }

    @Test
    @DisplayName("verifier 나 challenge 가 한 글자라도 다르면 불일치")
    void tampered_values_do_not_match() {
        assertThat(Pkce.matches(VERIFIER + "x", CHALLENGE)).isFalse();
        assertThat(Pkce.matches(VERIFIER, CHALLENGE.substring(1))).isFalse();
        assertThat(Pkce.matches(null, CHALLENGE)).isFalse();
        assertThat(Pkce.matches(VERIFIER, null)).isFalse();
    }

    @Test
    @DisplayName("challenge 형식 검사: S256 결과인 base64url 정확히 43자만 허용")
    void challenge_format() {
        assertThat(Pkce.isValidChallenge(CHALLENGE)).isTrue();
        assertThat(Pkce.isValidChallenge(null)).isFalse();
        assertThat(Pkce.isValidChallenge("short")).isFalse();
        assertThat(Pkce.isValidChallenge(CHALLENGE + "=")).isFalse();
        // 43자 초과는 SHA-256 결과일 수 없다 (verifier 길이 규칙 43~128 과 혼동하지 않는다)
        assertThat(Pkce.isValidChallenge(CHALLENGE + "A")).isFalse();
        assertThat(Pkce.isValidChallenge("a".repeat(128))).isFalse();
        // verifier 문자 집합의 "._~" 는 base64url 에 없다
        assertThat(Pkce.isValidChallenge(CHALLENGE.substring(0, 42) + "~")).isFalse();
    }
}
