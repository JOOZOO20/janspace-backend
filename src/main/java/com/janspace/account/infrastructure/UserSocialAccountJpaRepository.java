package com.janspace.account.infrastructure;

import com.janspace.account.domain.UserSocialAccount;
import com.janspace.account.domain.UserSocialAccountRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * UserSocialAccountRepository 의 Spring Data 구현. 파생 쿼리 이름은 도메인 인터페이스 선언과 같다.
 */
public interface UserSocialAccountJpaRepository extends UserSocialAccountRepository, JpaRepository<UserSocialAccount, Long> {

    @Override
    @Modifying
    @Query("delete from UserSocialAccount a where a.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
