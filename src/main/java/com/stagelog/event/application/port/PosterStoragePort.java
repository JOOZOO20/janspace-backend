package com.stagelog.event.application.port;

/**
 * 포스터 이미지 저장소 (S3). 구현은 event/infrastructure.
 */
public interface PosterStoragePort {

    // 저장 후 키를 돌려준다
    String put(String key, byte[] bytes, String contentType);

    // 내용 주소 key(prefix + sha256 앞 16자 + "." + extension)로 저장하고 key 를 돌려준다. 같은 key 가 있으면 PUT 하지 않는다.
    // 새 객체는 Cache-Control: public, max-age=31536000, immutable
    String putVersioned(String prefix, String extension, byte[] bytes, String contentType);

    // 저장된 객체 내용. 없는 key 면 예외 (key 백필용)
    byte[] get(String key);

    String publicUrl(String key);
}
