package com.janspace.common.exception.predefined;

import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;

/**
 * HTTP 401 인증 자체 실패 을 뜻하는 표준 예외. 새 예외 클래스를 만들기 전에 이 예외 + ErrorCode 조합을 우선 사용한다 (exception.md §2 1순위).
 */
public class UnauthorizedException extends ApplicationException {

    public UnauthorizedException(ErrorCode errorCode) {
        super(errorCode);
    }

    public UnauthorizedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
