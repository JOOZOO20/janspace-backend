package com.stagelog.catalog.application.port;

/**
 * 앨범커버 저장소. 구현은 S3 (infrastructure.S3CoverStorage).
 */
public interface CoverStoragePort {

    // 저장 후 키를 그대로 돌려준다
    String put(String key, byte[] bytes, String contentType);

    String publicUrl(String key);
}
