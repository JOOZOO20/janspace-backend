package com.stagelog.common.exception.predefined;

import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;

/**
 * HTTP 403 인증은 됐지만 권한 부족 을 뜻하는 표준 예외. 새 예외 클래스를 만들기 전에 이 예외 + ErrorCode 조합을 우선 사용한다.
 */
public class ForbiddenException extends ApplicationException {

    public ForbiddenException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ForbiddenException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
