package com.stagelog.account.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSocialAccountRepository extends JpaRepository<UserSocialAccount, Long> {

    Optional<UserSocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    List<UserSocialAccount> findAllByUserId(Long userId);

    boolean existsByUserIdAndProvider(Long userId, SocialProvider provider);

    // 탈퇴 시 한 문장으로 지운다 (파생 deleteBy 는 행마다 조회 후 삭제)
    @Modifying
    @Query("delete from UserSocialAccount a where a.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
