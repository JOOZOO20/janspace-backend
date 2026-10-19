package com.stagelog.setlist.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 리프레시 토큰 AES-GCM 변환: 키가 있으면 v1: 접두어 암호문, 없으면 평문. 접두어 없는 값은 키 도입 전 평문으로 읽는다.
 */
class RefreshTokenCipherTest {

    static final String KEY = "c3RhZ2Vsb2ctdGVzdC15b3V0dWJlLXRva2VuLWtleSE=";

    @Test
    @DisplayName("키가 있으면 매번 다른 암호문(무작위 IV)으로 저장하고 원문으로 되읽는다")
    void encrypts_and_decrypts() {
        RefreshTokenCipher cipher = new RefreshTokenCipher(KEY);

        String first = cipher.convertToDatabaseColumn("1//0gToken");
        String second = cipher.convertToDatabaseColumn("1//0gToken");

        assertThat(first).startsWith("v1:").doesNotContain("0gToken").isNotEqualTo(second);
        assertThat(cipher.convertToEntityAttribute(first)).isEqualTo("1//0gToken");
    }

    @Test
    @DisplayName("키가 없으면 평문 그대로, 키가 있어도 접두어 없는 기존 평문 행은 그대로 읽는다")
    void plaintext_without_key_and_legacy_rows() {
        RefreshTokenCipher none = new RefreshTokenCipher("");

        assertThat(none.convertToDatabaseColumn("1//0gToken")).isEqualTo("1//0gToken");
        assertThat(new RefreshTokenCipher(KEY).convertToEntityAttribute("1//0gToken")).isEqualTo("1//0gToken");
    }

    @Test
    @DisplayName("다른 키로 만든 암호문이나 32바이트가 아닌 키는 실패한다")
    void wrong_key_fails() {
        String encrypted = new RefreshTokenCipher(KEY).convertToDatabaseColumn("1//0gToken");
        String otherKey = "b3RoZXItdGVzdC15b3V0dWJlLXRva2VuLWtleSEhISE=";

        assertThatThrownBy(() -> new RefreshTokenCipher(otherKey).convertToEntityAttribute(encrypted)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RefreshTokenCipher("c2hvcnQ=")).isInstanceOf(IllegalStateException.class);
    }
}
