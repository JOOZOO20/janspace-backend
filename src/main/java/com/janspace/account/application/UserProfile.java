package com.janspace.account.application;

import com.janspace.account.domain.SocialProvider;
import com.janspace.account.domain.User;
import java.util.List;

/**
 * /me 응답 재료. 사용자와 연결된 제공자 목록 (소셜 행은 @OneToMany 로 매핑하지 않아 따로 조회한다).
 */
public record UserProfile(User user, List<SocialProvider> providers) {
}
