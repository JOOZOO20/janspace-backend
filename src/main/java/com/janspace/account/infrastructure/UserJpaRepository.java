package com.janspace.account.infrastructure;

import com.janspace.account.domain.User;
import com.janspace.account.domain.UserRepository;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * UserRepository 의 Spring Data 구현. 프록시가 도메인 인터페이스와 JpaRepository 를 함께 구현한다.
 */
public interface UserJpaRepository extends UserRepository, JpaRepository<User, Long> {

    @Override
    @Query("select u from User u where u.id = :id and u.deletedAt is null")
    Optional<User> findActiveById(@Param("id") Long id);
}
