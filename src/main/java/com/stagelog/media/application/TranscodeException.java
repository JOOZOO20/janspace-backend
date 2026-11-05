package com.stagelog.media.application;

/**
 * rendition 변환 실패. HTTP 요청이 아니라 Worker 안에서 나므로 ErrorCode 대신 사유(Reason)를 갖는다.
 * Worker 는 사유를 rendition 의 last_error 에 남기고, retryable() 이 false 면 재시도 없이 FAILED 로 끝낸다.
 */
public class TranscodeException extends RuntimeException {

    public enum Reason {
        // 입구 검증 실패(형식 위장, 잘린 파일, 크기 상한 초과). 같은 원본은 다시 해도 실패하므로 영구 실패
        INVALID_SOURCE,
        // 실행 시간 제한 초과. 프로세스는 종료됐다
        TIMEOUT,
        // FFmpeg·ffprobe 가 0 이 아닌 코드로 끝남
        FFMPEG_FAILED,
        // 출력이 디코딩되지 않거나 비었거나 해상도가 기대값(±1px)과 다름
        OUTPUT_INVALID
    }

    private final Reason reason;

    public TranscodeException(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public TranscodeException(Reason reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public boolean retryable() {
        return reason != Reason.INVALID_SOURCE;
    }
}
