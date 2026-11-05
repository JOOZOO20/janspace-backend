package com.stagelog.ops.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.stagelog.ops.application.DeadLetters.DeadLetter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * DLQ 메시지 항목. 오류 정보는 DLQ 메시지에 없어 queue_message_failures 에서 읽는다.
 */
@Schema(description = "DLQ 메시지 항목")
public record DeadLetterResponse(

        @Schema(description = "메시지 ID (본문의 messageId, redrive 요청에 그대로 쓴다)", example = "6f1c0d1e-8a4b-4f0e-9b7a-2c3d4e5f6a7b",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String messageId,

        @Schema(description = "원래 Queue 에서 처리에 실패한 횟수 (실패 기록이 없으면 SQS 수신 횟수)", example = "5",
                requiredMode = Schema.RequiredMode.REQUIRED)
        int receiveCount,

        @Schema(description = "마지막 오류 (예외 이름: 메시지). 실패 기록이 없으면 null", example = "ResourceAccessException: Read timed out",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String lastError,

        @Schema(description = "마지막 실패 시각 (UTC). 실패 기록이 없으면 null", example = "2026-11-05T12:02:00Z",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant lastFailedAt,

        @Schema(description = "본문 앞부분 (최대 200자)", example = "{\"messageId\":\"6f1c...\",\"eventId\":42,\"sourceUrl\":\"http://www.kopis.or.kr/...\"}",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String bodySummary
) {

    static DeadLetterResponse from(DeadLetter letter) {
        return new DeadLetterResponse(letter.messageId(), letter.attempts(), letter.lastError(), letter.lastFailedAt(),
                letter.bodySummary());
    }
}
