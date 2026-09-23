package com.janspace.common.exception;

/**
 * 스택 트레이스를 만들지 않는 경량 비즈니스 예외. 매우 자주 발생하고 스택이 디버깅에 무의미한 검증 실패에 쓴다 (exception.md §2 2순위).
 */
public class QuietApplicationException extends ApplicationException {

    public QuietApplicationException(ErrorCode errorCode) {
        super(errorCode);
    }

    public QuietApplicationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    // 스택 채우기를 생략해 throw 비용을 낮춘다
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
