package com.janspace.common.exception.predefined;

import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;

/**
 * HTTP 409 중복·동시성 충돌 을 뜻하는 표준 예외. 새 예외 클래스를 만들기 전에 이 예외 + ErrorCode 조합을 우선 사용한다.
 */
public class ConflictException extends ApplicationException {

    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
