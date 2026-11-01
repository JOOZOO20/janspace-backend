package com.stagelog.account.application.port;

import java.time.Instant;
import java.util.Optional;

/**
 * 리프레시 토큰 가족 저장소 (Redis). 토큰은 불투명 랜덤값이며 가족(familyId)은 한 로그인 세션의 회전 사슬이다.
 */
public interface RefreshTokenStore {

    void save(String token, Entry entry);

    Optional<Entry> find(String token);

    // 기존 값 비교·used 표시·후속 토큰 저장을 원자적으로 처리한다. 이미 사용된 토큰이면 가족을 폐기하고 false.
    boolean rotate(String token, Entry expected, String nextToken, Instant expiresAt);

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
