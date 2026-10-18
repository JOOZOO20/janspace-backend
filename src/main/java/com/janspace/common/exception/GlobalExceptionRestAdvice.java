package com.janspace.common.exception;

import com.janspace.common.dto.ResponseDTO;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전역 예외 핸들러. exception.md §5 표의 예외를 ResponseDTO 봉투로 바꾸고 한 번만 로깅한다.
 * 4xx 는 WARN(스택 없음), 5xx 는 ERROR(스택 포함).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionRestAdvice {

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ResponseDTO<Void>> handleApplication(ApplicationException e) {
        ErrorCode code = e.getErrorCode();
        // 5xx 만 스택을 남긴다. Quiet 예외는 스택이 없으므로 WARN 한 줄로 끝난다
        if (code.getHttpStatus().is5xxServerError()) {
            log.error("[비즈니스 예외 - 서버 오류] code={}, message={}", code, e.getMessage(), e);
        } else {
            log.warn("[비즈니스 예외] code={}, message={}", code, e.getMessage());
        }
        return respond(code, e.getMessage());
    }

    // @Valid 실패. BindException 은 MethodArgumentNotValidException 의 부모이므로 한 핸들러로 처리
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ResponseDTO<Void>> handleBind(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(GlobalExceptionRestAdvice::describe)
                .orElse(ErrorCode.INVALID_INPUT.getMessage());
        log.warn("[검증 실패 - 입력값] errors={}", detail);
        return respond(ErrorCode.INVALID_INPUT, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResponseDTO<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("[검증 실패 - 요청 본문 파싱] message={}", e.getMostSpecificCause().getMessage());
        return respond(ErrorCode.INVALID_INPUT, ErrorCode.INVALID_INPUT.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseDTO<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[검증 실패 - 잘못된 인자] message={}", e.getMessage());
        return respond(ErrorCode.INVALID_INPUT, ErrorCode.INVALID_INPUT.getMessage());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ResponseDTO<Void>> handleEntityNotFound(EntityNotFoundException e) {
        log.warn("[조회 실패 - JPA 엔티티 없음] message={}", e.getMessage());
        return respond(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage());
    }

    // 존재하지 않는 경로. 공개 GET 도 봉투 형태의 404 를 받아야 한다
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ResponseDTO<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("[조회 실패 - 경로 없음] path={}", e.getResourcePath());
        return respond(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ResponseDTO<Void>> handleDataAccess(DataAccessException e) {
        log.error("[시스템 오류 - 데이터 접근] message={}", e.getMostSpecificCause().getMessage(), e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ResponseDTO<Void>> handleRuntime(RuntimeException e) {
        log.error("[시스템 오류 - 예상하지 못한 예외] type={}", e.getClass().getSimpleName(), e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage());
    }

    private static ResponseEntity<ResponseDTO<Void>> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.getHttpStatus()).body(ResponseDTO.errorWithMessage(code, message));
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
