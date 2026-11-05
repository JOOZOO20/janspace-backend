package com.stagelog.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 모든 비즈니스 오류 코드. 응답 code 에는 enum 이름이, message 에는 한국어 메시지가 그대로 노출된다.
 * 기능이 추가되면 해당 영역 주석 아래에 도메인 prefix 를 붙여 추가한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    CONSENT_REQUIRED(HttpStatus.FORBIDDEN, "약관 동의가 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "이미 처리된 요청이거나 충돌이 발생했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type 입니다."),
    UPLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "업로드 크기가 허용 한도를 넘었습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // account
    UNSUPPORTED_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 로그인 제공자입니다."),
    INVALID_PKCE(HttpStatus.BAD_REQUEST, "code_challenge 가 없거나 형식이 올바르지 않습니다."),
    INVALID_AUTH_CODE(HttpStatus.UNAUTHORIZED, "인증 코드가 유효하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "리프레시 토큰이 유효하지 않습니다."),
    OAUTH_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "소셜 로그인 제공자 응답에 실패했습니다."),
    SOCIAL_ACCOUNT_ALREADY_LINKED(HttpStatus.CONFLICT, "다른 사용자에게 이미 연결된 소셜 계정입니다."),
    PROVIDER_ALREADY_LINKED(HttpStatus.CONFLICT, "같은 제공자의 계정이 이미 연결되어 있습니다."),
    PROVIDER_NOT_LINKED(HttpStatus.NOT_FOUND, "연결되지 않은 소셜 제공자입니다."),
    CANNOT_UNLINK_LAST_PROVIDER(HttpStatus.CONFLICT, "마지막 로그인 수단은 연결 해제할 수 없습니다."),
    AVATAR_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "프로필 사진은 5MB 이하, 긴 변 1024px 이하의 PNG 또는 JPEG 여야 합니다."),

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
    SYNC_SOURCE_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 동기화 출처입니다."),
    SYNC_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "외부 제공자 동기화에 실패했습니다."),
    SYNC_ALREADY_RUNNING(HttpStatus.CONFLICT, "같은 출처의 동기화가 이미 실행 중입니다. 잠시 후 다시 시도해 주세요."),
    EVENT_IN_USE(HttpStatus.CONFLICT, "참석·기록·셋리 등 사용자 데이터가 연결된 행사는 삭제할 수 없습니다. 대신 취소로 표시해 주세요."),

    // catalog
    SONG_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 곡입니다."),
    ALBUM_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 앨범입니다."),
    CATALOG_IMPORT_INVALID(HttpStatus.BAD_REQUEST, "곡 마스터 파일 형식이 올바르지 않습니다."),
    COVER_INVALID(HttpStatus.BAD_REQUEST, "앨범커버는 5MB 이하의 PNG 또는 JPEG 이미지여야 합니다."),

    // setlist
    REVISION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 셋리 리비전입니다."),
    SETLIST_EDIT_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "오늘 셋리 편집 횟수를 모두 사용했습니다."),
    SETLIST_VERSION_CONFLICT(HttpStatus.CONFLICT, "다른 사용자가 먼저 셋리를 편집했습니다. 새로 고친 뒤 다시 시도해 주세요."),
    UNMATCHED_NAME_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 미매칭 곡명입니다."),
    YOUTUBE_NOT_CONNECTED(HttpStatus.CONFLICT, "유튜브 채널이 아직 연결되지 않았습니다."),
    YOUTUBE_API_ERROR(HttpStatus.BAD_GATEWAY, "유튜브 API 호출에 실패했습니다."),
    YOUTUBE_RECONNECT_REQUIRED(HttpStatus.CONFLICT, "유튜브 채널 연결이 만료되었습니다. 다시 연결해 주세요."),
    PLAYLIST_SYNC_IN_PROGRESS(HttpStatus.CONFLICT, "이 행사의 재생목록 동기화가 이미 진행 중입니다. 잠시 후 다시 시도해 주세요."),
    YOUTUBE_QUOTA_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS, "오늘 유튜브 API 할당량이 부족합니다. 태평양 시간 자정 이후 다시 시도해 주세요."),

    // attendance
    RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 기록입니다."),
    RECORD_ALREADY_EXISTS(HttpStatus.CONFLICT, "이 행사에는 이미 기록이 있습니다."),
    EVENT_NOT_ENDED(HttpStatus.BAD_REQUEST, "행사 날짜가 되기 전에는 기록할 수 없습니다."),
    INVALID_EMOTIONS(HttpStatus.BAD_REQUEST, "감정은 1~3개를 중복 없이 골라야 합니다."),
    DEPARTURE_REQUIRED(HttpStatus.CONFLICT, "출발지를 먼저 설정해야 합니다."),
    CHAIN_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "직전 공연이 5일 이내의 다른 도시일 때만 연속 이동으로 기록할 수 있습니다."),

    // photocard
    PHOTOCARD_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 포토카드입니다."),
    PHOTOCARD_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "이번 달 포토카드 개수 제한을 넘었습니다."),
    PHOTOCARD_IMAGE_INVALID(HttpStatus.BAD_REQUEST, "PNG 또는 JPEG 이미지가 아니거나 크기 제한을 넘었습니다."),
    STICKER_SET_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 스티커 세트입니다."),
    STICKER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 스티커입니다."),
    PHOTOCARD_ALREADY_LINKED(HttpStatus.CONFLICT, "이 기록에는 이미 다른 포토카드가 연결되어 있습니다."),
    FRAME_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 프레임입니다."),
    FRAME_SVG_INVALID(HttpStatus.BAD_REQUEST, "프레임 파일은 스크립트가 없는 1:3 SVG 여야 합니다."),

    // ticket
    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 티켓입니다."),
    TICKET_ALREADY_EXISTS(HttpStatus.CONFLICT, "이 행사의 티켓이 이미 티켓북에 있습니다."),
    TICKET_SKIN_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 티켓 스킨입니다."),
    TICKET_SKIN_DEFAULT(HttpStatus.BAD_REQUEST, "기본 스킨은 지울 수 없습니다."),

    // notification
    PUSH_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 푸시 잡입니다."),
    PUSH_DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 푸시 배달입니다."),
    PUSH_SUBSCRIPTION_INVALID(HttpStatus.BAD_REQUEST, "푸시 구독 정보가 올바르지 않습니다."),
    PUSH_DELIVERY_NOT_RETRYABLE(HttpStatus.CONFLICT, "실패(FAILED) 상태의 배달만 다시 시도할 수 있습니다."),

    // ops
    QUEUE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 Queue 입니다."),
    DEAD_LETTER_NOT_FOUND(HttpStatus.NOT_FOUND, "DLQ 에서 메시지를 찾지 못했습니다. 이미 재투입됐거나 없는 메시지입니다."),

    // ops (MCP 조치 요청)
    AGENT_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 조치 요청입니다."),
    AGENT_REQUEST_NOT_PENDING(HttpStatus.CONFLICT, "승인 대기(PENDING) 상태의 조치 요청만 승인·거절할 수 있습니다."),
    AGENT_ACTION_NOT_EXECUTABLE(HttpStatus.CONFLICT, "아직 실행 경로가 연결되지 않은 조치 유형입니다."),
    ;

    private final HttpStatus httpStatus;
    private final String message;
}
