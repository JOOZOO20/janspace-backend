package com.stagelog.ops.application;

/**
 * 다시 처리해도 성공하지 않는 메시지 실패 (예: 포스터 404·410, 이미지 검증 실패). Consumer 는 실패를 기록하고 메시지를 삭제한다.
 */
public class PermanentMessageFailure extends RuntimeException {

    public PermanentMessageFailure(String message) {
        super(message);
    }

    public PermanentMessageFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
