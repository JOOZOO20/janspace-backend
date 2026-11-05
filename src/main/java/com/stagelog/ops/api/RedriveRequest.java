package com.stagelog.ops.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * DLQ redrive 요청. 목록 조회에서 받은 messageId 를 그대로 넣는다.
 */
@Schema(description = "DLQ 메시지를 원래 Queue 로 재투입하는 요청")
public record RedriveRequest(
        @Schema(description = "재투입할 메시지 ID 목록 (1~100건)", example = "[\"6f1c0d1e-8a4b-4f0e-9b7a-2c3d4e5f6a7b\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotEmpty @Size(max = 100) List<@NotBlank String> messageIds) {
}
