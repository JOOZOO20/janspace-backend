package com.stagelog.event.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "팬 제안 등록 요청")
public record ProposalRequest(
        @Schema(description = "제안 내용 (1~2000자)", example = "공연장이 KSPO DOME 으로 바뀌었어요", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 2000) String text) {
}
