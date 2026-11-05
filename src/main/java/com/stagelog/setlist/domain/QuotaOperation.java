package com.stagelog.setlist.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * YouTube Data API 호출 종류와 단가(unit). 목록 조회 1, 생성·수정·삭제 50 (https://developers.google.com/youtube/v3/determine_quota_cost).
 * playlists.list 는 LIST, playlists.insert 는 INSERT 로 센다.
 */
@Getter
@RequiredArgsConstructor
public enum QuotaOperation {
    LIST(1), INSERT(50), UPDATE(50), DELETE(50);

    private final int units;
}
