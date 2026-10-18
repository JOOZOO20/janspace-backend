package com.janspace.account.application;

import java.util.Optional;

/**
 * 다른 기능에 공개하는 사용자 조회 계약. 탈퇴(소프트 삭제)한 사용자는 비어 있다.
 */
public interface AccountQuery {

    Optional<AccountSnapshot> find(Long userId);
}
