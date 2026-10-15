package com.janspace.account.domain;

import java.util.Optional;

/**
 * 사용자 저장소 추상화. 구현은 infrastructure 의 Spring Data 인터페이스가 맡는다.
 */
public interface UserRepository {

    User save(User user);

    // 소프트 삭제된 사용자는 제외한다
    Optional<User> findActiveById(Long id);

    // 같은 사용자의 로그인 수단을 바꾸는 요청끼리 직렬화한다 (트랜잭션 안에서만 호출)
    Optional<User> findActiveByIdForUpdate(Long id);
}
