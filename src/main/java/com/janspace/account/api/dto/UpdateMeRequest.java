package com.janspace.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * 프로필 부분 수정. null 인 필드는 바꾸지 않는다.
 */
@Schema(description = "프로필 수정 요청 (PATCH). 보낸 필드만 반영")
public record UpdateMeRequest(
        @Schema(description = "닉네임", example = "달빛 팬", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(min = 1, max = 50) String nickname,
        @Schema(description = "표시 언어 (BCP-47)", example = "ja", requiredMode = Schema.RequiredMode.NOT_REQUIRED,
                allowableValues = {"ko", "en", "zh-TW", "ja"})
        String lang,
        @Schema(description = "출발지", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid DepartureDto departure) {
}
