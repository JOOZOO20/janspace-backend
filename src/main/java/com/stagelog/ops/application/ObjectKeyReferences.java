package com.stagelog.ops.application;

/**
 * S3 key 를 현재 DB 가 참조하는지 알려주는 공개 계약. key 를 저장하는 기능(event 포스터, catalog 커버)이 구현하고,
 * 삭제 배치(StorageCleanup)는 구현 전체에 물어 하나라도 참조하면 지우지 않는다. ops 가 기능 패키지를 참조하지 않게 하는 역방향 계약이다.
 */
public interface ObjectKeyReferences {

    boolean isReferenced(String key);
}
