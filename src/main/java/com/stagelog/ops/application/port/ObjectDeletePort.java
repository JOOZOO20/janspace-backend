package com.stagelog.ops.application.port;

/**
 * 이미지 객체 삭제 포트 (S3). 구현은 ops/infrastructure.
 */
public interface ObjectDeletePort {

    // 없는 key 도 성공으로 본다
    void delete(String key);
}
