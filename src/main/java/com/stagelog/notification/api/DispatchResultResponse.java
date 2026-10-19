package com.stagelog.notification.api;

import com.stagelog.notification.application.DispatchDuePushJobs.CycleResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "디스패처 한 사이클의 처리 건수")
public record DispatchResultResponse(
        @Schema(description = "선점한 잡 수", example = "1", requiredMode = Schema.RequiredMode.REQUIRED) int claimed,
        @Schema(description = "행사 취소로 취소한 잡 수", example = "0", requiredMode = Schema.RequiredMode.REQUIRED) int cancelled,
        @Schema(description = "물질화한 잡 수", example = "1", requiredMode = Schema.RequiredMode.REQUIRED) int materialized,
        @Schema(description = "전송 성공(SENT) 배달 수", example = "8", requiredMode = Schema.RequiredMode.REQUIRED) int sent,
        @Schema(description = "회수한 고아 SENDING 수", example = "0", requiredMode = Schema.RequiredMode.REQUIRED) int recovered,
        @Schema(description = "DONE 으로 바꾼 잡 수", example = "1", requiredMode = Schema.RequiredMode.REQUIRED) int completed) {

    static DispatchResultResponse from(CycleResult r) {
        return new DispatchResultResponse(r.claimed(), r.cancelled(), r.materialized(), r.sent(), r.recovered(), r.completed());
    }
}
