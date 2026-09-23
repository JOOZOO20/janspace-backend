package com.janspace.account.application.event;

/**
 * 사용자 탈퇴 도메인 이벤트. setlist·notification·attendance·photocard 가 수신한다.
 */
public record UserWithdrawn(Long userId) {
}
