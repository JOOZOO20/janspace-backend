package com.stagelog.account.application.port;

/**
 * 프로필 사진 저장소. 구현은 S3 (infrastructure.S3AvatarStorage). 키 규칙(avatars/{userId}/{uuid}.{ext})은 application 이 정한다.
 */
public interface AvatarStoragePort {

    void put(String key, byte[] bytes, String contentType);

    // 없는 키여도 오류가 아니다
    void delete(String key);

    String publicUrl(String key);
}
