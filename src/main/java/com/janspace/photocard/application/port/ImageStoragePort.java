package com.janspace.photocard.application.port;

/**
 * 포토카드·스티커·프레임 파일 저장소. 구현은 S3 (infrastructure.S3ImageStorage). 키 규칙은 application 이 정한다.
 */
public interface ImageStoragePort {

    // 저장 후 키를 그대로 돌려준다
    String put(String key, byte[] bytes, String contentType);

    // 없는 키도 성공한다
    void delete(String key);

    String publicUrl(String key);
}
