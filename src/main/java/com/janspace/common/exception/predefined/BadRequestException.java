package com.janspace.common.exception.predefined;

import com.janspace.common.exception.ApplicationException;
import com.janspace.common.exception.ErrorCode;

/**
 * HTTP 400 잘못된 요청 입력 을 뜻하는 표준 예외. 새 예외 클래스를 만들기 전에 이 예외 + ErrorCode 조합을 우선 사용한다.
 */
public class BadRequestException extends ApplicationException {

    public BadRequestException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BadRequestException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
