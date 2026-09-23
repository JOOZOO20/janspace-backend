package com.janspace.account.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * PKCE S256 (RFC 7636). challenge = BASE64URL(SHA-256(verifier)), 패딩 없음.
 */
public final class Pkce {

    // RFC 7636 §4.2: 43~128자의 base64url 문자
    private static final Pattern CHALLENGE = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");

    private Pkce() {
    }

    public static boolean isValidChallenge(String challenge) {
        return challenge != null && CHALLENGE.matcher(challenge).matches();
    }

    public static String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", e);
        }
    }

    // 상수 시간 비교로 타이밍 차이를 없앤다
    public static boolean matches(String verifier, String challenge) {
        if (verifier == null || challenge == null) {
            return false;
        }
        return MessageDigest.isEqual(
                challengeOf(verifier).getBytes(StandardCharsets.US_ASCII),
                challenge.getBytes(StandardCharsets.US_ASCII));
    }
}
