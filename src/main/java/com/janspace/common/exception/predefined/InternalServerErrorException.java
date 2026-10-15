package com.janspace.common.exception.predefined;

import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;

/**
 * HTTP 500 시스템 내부 처리 실패 을 뜻하는 표준 예외. 새 예외 클래스를 만들기 전에 이 예외 + ErrorCode 조합을 우선 사용한다.
 */
public class InternalServerErrorException extends ApplicationException {

    public InternalServerErrorException(ErrorCode errorCode) {
        super(errorCode);
    }

    public InternalServerErrorException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
