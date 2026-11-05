package com.stagelog.ops.api;

import com.stagelog.ops.application.DeadLetters.Redrive;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * DLQ redrive 결과.
 */
@Schema(description = "DLQ redrive 결과")
public record RedriveResponse(
        @Schema(description = "원래 Queue 로 재투입한 메시지 ID", example = "[\"6f1c0d1e-8a4b-4f0e-9b7a-2c3d4e5f6a7b\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> redriven,
        @Schema(description = "DLQ 에서 찾지 못한 메시지 ID (이미 재투입됐거나 없는 ID)", example = "[]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> notFound) {

    static RedriveResponse from(Redrive redrive) {
        return new RedriveResponse(redrive.redriven(), redrive.notFound());
    }
}
