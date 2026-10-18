package com.janspace.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 모든 비즈니스 오류 코드. 응답 code 에는 enum 이름이, message 에는 한국어 메시지가 그대로 노출된다 (exception.md §3).
 * 기능이 추가되면 해당 영역 주석 아래에 도메인 prefix 를 붙여 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    CONSENT_REQUIRED(HttpStatus.UNAUTHORIZED, "약관 동의가 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "이미 처리된 요청이거나 충돌이 발생했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // account

    // event

    // catalog

    // setlist

    // attendance

    // photocard

    // notification

    // ops
    ;

    private final HttpStatus httpStatus;
    private final String message;
}
