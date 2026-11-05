package com.stagelog.ops.application;

import com.stagelog.ops.domain.JobLeaseRepository;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 잡 Lease 공개 계약. 여러 서버 중 하나만 같은 잡을 실행하게 한다. 각 메서드는 REQUIRES_NEW 로 바로 커밋한다
 * (호출자 트랜잭션에 묶이면 획득·해제가 그 트랜잭션이 끝날 때까지 다른 서버에 안 보인다).
 */
@Service
@RequiredArgsConstructor
public class JobLeases {

    private final JobLeaseRepository repository;

    /** 지금 Lease 를 쥔 소유자와 만료 시각(DB 시계). */
    public record Holder(String ownerId, Instant expiresAt) {
    }

    public static String syncKey(String source) {
        return "sync:" + source;
    }

    // 획득하면 fencing token, 이미 다른 소유자가 쥐고 있으면 empty
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> tryAcquire(String key, String ownerId, long ttlSeconds) {
        if (repository.acquire(key, ownerId, ttlSeconds) != 1) {
            return Optional.empty();
        }
        // 같은 트랜잭션이 방금 갱신해 잠근 행이라 다른 획득과 섞이지 않는다
        return repository.findToken(key);
    }

    // false = Lease 상실
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean heartbeat(String key, String ownerId, long token, long ttlSeconds) {
        return repository.heartbeat(key, ownerId, token, ttlSeconds) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(String key, String ownerId, long token) {
        return repository.release(key, ownerId, token) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<Holder> holder(String key) {
        return repository.findHeld(key).map(l -> new Holder(l.getOwnerId(), l.getExpiresAt()));
    }
}
