package com.janspace.account.domain;

import java.util.List;
import java.util.Optional;

/**
 * 소셜 로그인 수단 저장소 추상화.
 */
public interface UserSocialAccountRepository {

    UserSocialAccount save(UserSocialAccount account);

    Optional<UserSocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);

    List<UserSocialAccount> findAllByUserId(Long userId);

    boolean existsByUserIdAndProvider(Long userId, SocialProvider provider);

    void deleteAllByUserId(Long userId);

    void delete(UserSocialAccount account);
}
