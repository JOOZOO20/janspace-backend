package com.janspace.common.dto;

import com.janspace.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * 모든 REST 응답의 공통 봉투 {code, data, message}. 생성자는 private 이며 정적 팩토리만 사용한다.
 * error/errorWithMessage 는 GlobalExceptionRestAdvice 전용이다.
 */
@Getter
@Schema(description = "API 공통 응답 봉투")
public final class ResponseDTO<T> {

    private static final String OK_CODE = "OK";

    @Schema(description = "결과 코드. 성공은 OK, 실패는 ErrorCode 이름", example = "OK",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private final String code;

    @Schema(description = "응답 데이터. 없으면 null", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private final T data;

    @Schema(description = "사용자에게 보여줄 메시지. 없으면 null", example = "요청이 처리되었습니다.",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private final String message;

    private ResponseDTO(String code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public static ResponseDTO<Void> ok() {
        return new ResponseDTO<>(OK_CODE, null, null);
    }

    public static <T> ResponseDTO<T> okWithData(T data) {
        return new ResponseDTO<>(OK_CODE, data, null);
    }

    public static <T> ResponseDTO<T> okWithMessageAndData(String message, T data) {
        return new ResponseDTO<>(OK_CODE, data, message);
    }

    // 핸들러 전용. 도메인·컨트롤러에서는 predefined 예외를 throw 한다
    public static ResponseDTO<Void> error(ErrorCode errorCode) {
        return new ResponseDTO<>(errorCode.name(), null, errorCode.getMessage());
    }

    public static ResponseDTO<Void> errorWithMessage(ErrorCode errorCode, String message) {
        return new ResponseDTO<>(errorCode.name(), null, message);
    }
}
