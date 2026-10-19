package com.stagelog.common.exception;

import com.stagelog.common.dto.ResponseDTO;
import jakarta.persistence.EntityNotFoundException;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 전역 예외 핸들러. 모든 오류를 ResponseDTO 봉투로 바꾸고 한 번만 로깅한다. 4xx 는 WARN(스택 없음), 5xx 는 ERROR(스택 포함).
 * 스프링 MVC 표준 예외(타입 변환 실패, 필수 파라미터·파트 누락, 본문 파싱 실패, 405, 415, 413, 없는 경로 등)는
 * 부모 ResponseEntityExceptionHandler 가 상태 코드를 정하고 handleExceptionInternal 에서 봉투로 바꾼다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionRestAdvice extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ResponseDTO<Void>> handleApplication(ApplicationException e) {
        ErrorCode code = e.getErrorCode();
        // 5xx 만 스택을 남긴다(원인 예외가 있으면 Caused by 로 함께). 4xx 는 WARN 한 줄로 끝난다
        if (code.getHttpStatus().is5xxServerError()) {
            log.error("[비즈니스 예외 - 서버 오류] code={}, message={}", code, e.getMessage(), e);
        } else {
            log.warn("[비즈니스 예외] code={}, message={}", code, e.getMessage());
        }
        return respond(code, e.getMessage());
    }

    // MVC 인자 해석 밖에서 난 바인딩 실패. @Valid 본문 실패(MethodArgumentNotValidException)는 부모가 받아 같은 봉투로 바꾼다
    @ExceptionHandler(BindException.class)
    public ResponseEntity<Object> handleBind(BindException e) {
        return envelope(e, new HttpHeaders(), HttpStatus.BAD_REQUEST);
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

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ResponseDTO<Void>> handleDataAccess(DataAccessException e) {
        log.error("[시스템 오류 - 데이터 접근] message={}", e.getMostSpecificCause().getMessage(), e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage());
    }

    // 위 어디에도 맞지 않는 예외. 컨트롤러가 던진 검사 예외(IOException 등)도 봉투로 내린다
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDTO<Void>> handleUnexpected(Exception e) {
        log.error("[시스템 오류 - 예상하지 못한 예외] type={}", e.getClass().getSimpleName(), e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage());
    }

    // 부모는 405 를 PageNotFound 로거에 한 번 더 남긴다. 로그는 envelope 한 곳에서만 남긴다
    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, null, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return envelope(ex, headers, status);
    }

    // 스프링이 정한 상태 코드는 그대로 두고 code·message 만 봉투로 만든다. 헤더(405 의 Allow 등)도 유지한다
    private static ResponseEntity<Object> envelope(Exception ex, HttpHeaders headers, HttpStatusCode status) {
        ErrorCode code = codeOf(status);
        String message = status.is4xxClientError() ? clientMessage(ex, code) : code.getMessage();
        if (status.is5xxServerError()) {
            log.error("[시스템 오류 - 요청 처리] status={}, type={}", status.value(), ex.getClass().getSimpleName(), ex);
        } else {
            log.warn("[요청 거부] status={}, code={}, type={}, detail={}", status.value(), code,
                    ex.getClass().getSimpleName(), logDetail(ex, message));
        }
        // 406 은 클라이언트가 JSON 을 받지 않겠다는 뜻이라 봉투를 쓸 수 없다
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return ResponseEntity.status(status).headers(headers).build();
        }
        return ResponseEntity.status(status).headers(headers).body(ResponseDTO.errorWithMessage(code, message));
    }

    private static ErrorCode codeOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHORIZED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> ErrorCode.CONFLICT;
            case 413 -> ErrorCode.UPLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? ErrorCode.INVALID_INPUT : ErrorCode.INTERNAL_ERROR;
        };
    }

    // 사용자에게 보이는 메시지. 어느 입력이 문제인지만 알리고 입력값 자체는 되돌려 주지 않는다
    private static String clientMessage(Exception ex, ErrorCode code) {
        return switch (ex) {
            case BindException e -> e.getBindingResult().getFieldErrors().stream().findFirst()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .orElse(code.getMessage());
            case TypeMismatchException e -> e.getPropertyName() + ": 형식이 올바르지 않습니다." + allowedValues(e.getRequiredType());
            case MissingServletRequestParameterException e -> e.getParameterName() + ": 필수 파라미터가 없습니다.";
            case MissingServletRequestPartException e -> e.getRequestPartName() + ": 필수 파트가 없습니다.";
            case HttpMessageNotReadableException e -> "요청 본문을 읽을 수 없습니다. JSON 형식을 확인해 주세요.";
            default -> code.getMessage();
        };
    }

    // enum 쿼리·경로 값이 틀렸을 때 프론트가 허용값을 바로 알 수 있게 한다
    private static String allowedValues(Class<?> type) {
        return type != null && type.isEnum() ? " 허용값: " + Arrays.toString(type.getEnumConstants()) : "";
    }

    // 본문 파싱 실패는 원인 메시지가 있어야 디버깅이 된다. 나머지는 사용자 메시지로 충분하다(바인딩 실패의 거부값은 남기지 않는다)
    private static String logDetail(Exception ex, String message) {
        return ex instanceof HttpMessageNotReadableException e ? e.getMostSpecificCause().getMessage() : message;
    }

    private static ResponseEntity<ResponseDTO<Void>> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.getHttpStatus()).body(ResponseDTO.errorWithMessage(code, message));
    }
}
