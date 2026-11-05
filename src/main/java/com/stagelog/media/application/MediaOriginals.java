package com.stagelog.media.application;

import java.util.List;

/**
 * 백필 원본 목록 계약. 원본을 가진 기능(event·catalog·photocard)이 구현한다. media 가 그 기능의 저장소를 직접 보지 않게 하는 역방향 계약이다.
 */
public interface MediaOriginals {

    boolean supports(MediaOwnerType ownerType);

    // 소유자 ID 가 afterOwnerId 보다 큰 원본을 ID 순으로 최대 limit 개. 원본이 없는(key null) 소유자와 제공자 URL 원본은 뺀다
    List<Entry> after(MediaOwnerType ownerType, long afterOwnerId, int limit);

    record Entry(long ownerId, String key) {
    }
}
