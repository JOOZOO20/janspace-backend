package com.janspace.common.exception;

import lombok.Getter;

/**
 * 모든 비즈니스 예외의 부모. ErrorCode 를 보유하며 GlobalExceptionRestAdvice 가 HTTP 상태·응답·로그로 변환한다.
 */
@Getter
public class ApplicationException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApplicationException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    // 사용자 노출 메시지를 ErrorCode 기본값 대신 지정할 때. 민감정보 금지
    public ApplicationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
