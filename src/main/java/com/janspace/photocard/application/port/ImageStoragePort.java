package com.janspace.photocard.application.port;

/**
 * 포토카드·스티커 이미지 저장소. 구현은 S3 (infrastructure.S3ImageStorage). 키 규칙은 application 이 정한다.
 */
public interface ImageStoragePort {

    // 저장 후 키를 그대로 돌려준다
    String put(String key, byte[] bytes, String contentType);

    String publicUrl(String key);
}
