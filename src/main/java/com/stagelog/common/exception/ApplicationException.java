package com.stagelog.common.exception;

import lombok.Getter;

/**
 * 모든 비즈니스 예외의 부모. ErrorCode 를 보유하며 GlobalExceptionRestAdvice 가 HTTP 상태·응답·로그로 변환한다.
 */
@Getter
public class ApplicationException extends RuntimeException {

    private final ErrorCode errorCode;
    // 오류 응답의 data 로 함께 내릴 값 (예: 409 의 현재 소유자). 없으면 null. 민감정보 금지
    private final Object data;

    public ApplicationException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.data = null;
    }

    // 사용자 노출 메시지를 ErrorCode 기본값 대신 지정할 때. 민감정보 금지
    public ApplicationException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ApplicationException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    // 외부 호출 실패처럼 원인 예외가 있을 때. 핸들러의 5xx ERROR 로그에 원인 스택(Caused by)이 함께 남는다
    public ApplicationException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.data = null;
    }
}
