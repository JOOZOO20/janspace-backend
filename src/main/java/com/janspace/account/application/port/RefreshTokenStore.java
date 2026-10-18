package com.janspace.account.application.port;

import java.time.Instant;
import java.util.Optional;

/**
 * 리프레시 토큰 가족 저장소 (Redis). 토큰은 불투명 랜덤값이며 가족(familyId)은 한 로그인 세션의 회전 사슬이다.
 */
public interface RefreshTokenStore {

    void save(String token, Entry entry);

    Optional<Entry> find(String token);

    // 회전된 토큰을 짧은 TTL 로 used 표시해 재사용을 감지한다
    void markUsed(String token, Entry entry);

    void revokeFamily(String familyId);

    void revokeAllOf(Long userId);

    record Entry(Long userId, String familyId, Instant expiresAt, boolean used) {

        public Entry rotated(Instant expiresAt) {
            return new Entry(userId, familyId, expiresAt, false);
        }

        public Entry asUsed() {
            return new Entry(userId, familyId, expiresAt, true);
        }
    }
}
