package com.stagelog.account.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    // 소프트 삭제된 사용자는 제외한다
    @Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> findActiveById(@Param("id") Long id);

    // SELECT ... FOR UPDATE. 같은 사용자의 로그인 수단·프로필 사진을 바꾸는 요청끼리 직렬화한다 (트랜잭션 안에서만 호출)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> findActiveByIdForUpdate(@Param("id") Long id);
}
