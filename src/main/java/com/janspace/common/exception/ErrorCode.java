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
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 사용자입니다."),
    UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 로그인 제공자입니다."),
    INVALID_PKCE(HttpStatus.BAD_REQUEST, "code_challenge 가 없거나 형식이 올바르지 않습니다."),
    INVALID_AUTH_CODE(HttpStatus.UNAUTHORIZED, "인증 코드가 유효하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 유효하지 않습니다."),
    OAUTH_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "소셜 로그인 제공자 응답에 실패했습니다."),
    SOCIAL_ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT, "다른 사용자에게 이미 연결된 소셜 계정입니다."),
    PROVIDER_ALREADY_LINKED(HttpStatus.CONFLICT, "같은 제공자의 계정이 이미 연결되어 있습니다."),

    // event
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 행사입니다."),
    VENUE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 공연장입니다."),
    SUGGESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 제안입니다."),
    SOURCE_CHANGE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 출처 변경 요청입니다."),
    CANDIDATE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 매칭 후보입니다."),
    DISCOVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 발견 행사입니다."),
    EVENT_FIELD_NOT_LOCKABLE(HttpStatus.BAD_REQUEST, "잠글 수 없는 필드입니다."),
    INVALID_EVENT_SCHEDULE(HttpStatus.BAD_REQUEST, "행사 일정이 올바르지 않습니다."),
    POSTER_INVALID(HttpStatus.BAD_REQUEST, "포스터 이미지가 올바르지 않습니다."),

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
