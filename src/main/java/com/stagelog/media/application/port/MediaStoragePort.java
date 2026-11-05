package com.stagelog.media.application.port;

import java.util.Optional;

/**
 * 원본 읽기와 rendition 저장 (S3). 구현은 media/infrastructure.
 */
public interface MediaStoragePort {

    // 없는 key 면 empty (원본이 교체 후 삭제됐다)
    Optional<byte[]> get(String key);

    // key 에 출력 hash 가 들어 있으므로 같은 key 가 있으면 PUT 을 건너뛴다. 새 객체는 Cache-Control immutable (JAN-02 와 같다)
    void putImmutable(String key, byte[] bytes, String contentType);

    String publicUrl(String key);
}
