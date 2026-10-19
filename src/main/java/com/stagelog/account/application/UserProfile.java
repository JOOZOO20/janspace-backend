package com.stagelog.account.application;

import com.stagelog.account.application.port.AvatarStoragePort;
import com.stagelog.account.domain.SocialProvider;
import com.stagelog.account.domain.User;
import com.stagelog.account.domain.UserSocialAccount;
import com.stagelog.account.domain.UserSocialAccountRepository;
import java.util.List;

/**
 * /me 응답 재료. 사용자, 연결된 제공자 목록 (소셜 행은 @OneToMany 로 매핑하지 않아 따로 조회한다), 프로필 사진 공개 URL(없으면 null).
 */
public record UserProfile(User user, List<SocialProvider> providers, String avatarUrl) {

    static UserProfile of(User user, UserSocialAccountRepository socialAccounts, AvatarStoragePort avatars) {
        List<SocialProvider> providers = socialAccounts.findAllByUserId(user.getId()).stream()
                .map(UserSocialAccount::getProvider).toList();
        return new UserProfile(user, providers, user.getAvatarKey() == null ? null : avatars.publicUrl(user.getAvatarKey()));
    }
}
