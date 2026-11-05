package com.stagelog.media.application;

/**
 * 변환 원본의 소유자 종류. media_assets.owner_type 값이고, 원본을 저장하는 기능이 MediaAssets.register 에 넘긴다.
 */
public enum MediaOwnerType {
    EVENT_POSTER, ALBUM, PHOTOCARD, FOURCUT
}
