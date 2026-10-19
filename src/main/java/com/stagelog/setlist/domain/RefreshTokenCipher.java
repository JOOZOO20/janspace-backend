package com.stagelog.setlist.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

/**
 * youtube_credentials.refresh_token 저장 시 AES-256-GCM 암호화. 저장 형식은 "v1:" + base64(IV 12바이트 || 암호문+태그).
 * 키(YOUTUBE_TOKEN_KEY, base64 로 인코딩한 32바이트)가 없으면 기동은 막지 않고 평문으로 저장하며 경고를 남긴다.
 * 접두어가 없는 값은 키 도입 전 평문 행으로 보고 그대로 읽는다(다음 저장 때 암호화된다). Hibernate 가 Spring 빈 컨테이너로 만들어 키를 주입한다.
 */
@Slf4j
@Converter
public class RefreshTokenCipher implements AttributeConverter<String, String> {

    static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    // null 이면 평문 저장
    private final SecretKey key;

    public RefreshTokenCipher(@Value("${stagelog.youtube.token-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("[설정 경고] YOUTUBE_TOKEN_KEY 가 없어 유튜브 리프레시 토큰을 평문으로 저장합니다");
            this.key = null;
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("YOUTUBE_TOKEN_KEY 는 base64 로 인코딩한 32바이트여야 합니다", e);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("YOUTUBE_TOKEN_KEY 는 base64 로 인코딩한 32바이트여야 합니다");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String token) {
        if (token == null || key == null) {
            return token;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("유튜브 리프레시 토큰을 암호화하지 못했습니다", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("암호화된 유튜브 리프레시 토큰을 읽으려면 YOUTUBE_TOKEN_KEY 가 필요합니다");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
            return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("유튜브 리프레시 토큰을 복호화하지 못했습니다 (키가 바뀌었으면 채널을 다시 연결)", e);
        }
    }
}
