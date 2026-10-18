package com.janspace.event.application.port;

/**
 * 포스터 이미지 저장소 (S3). 구현은 event/infrastructure.
 */
public interface PosterStoragePort {

    // 저장 후 키를 돌려준다
    String put(String key, byte[] bytes, String contentType);

    String publicUrl(String key);
}
