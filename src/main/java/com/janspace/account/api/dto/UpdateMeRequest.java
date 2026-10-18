package com.janspace.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.Optional;

/**
 * 프로필 부분 수정. 키가 없으면 유지한다. lang·departure 는 null 이면 비운다 (Optional: 없음 = null, "x": null = empty).
 * nickname 은 비울 수 없어 null 도 유지로 본다. 앞뒤 공백을 떼고 검증하므로 공백만 있으면 400 이다.
 */
@Schema(description = "프로필 수정 요청 (PATCH). 보낸 필드만 반영. lang·departure 는 null 을 보내면 비운다")
public record UpdateMeRequest(
        @Schema(description = "닉네임 (앞뒤 공백 제거 후 1~50자). null 이면 유지", example = "달빛 팬", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(min = 1, max = 50) String nickname,
        @Schema(description = "표시 언어 (BCP-47). null 이면 기기 언어로 되돌린다", example = "ja",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED, allowableValues = {"ko", "en", "zh-TW", "ja"})
        Optional<String> lang,
        @Schema(description = "출발지. null 이면 지운다", example = "{\"name\":\"서울 노원구\",\"countryCode\":\"KR\",\"lat\":37.654,\"lng\":127.056}",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<@Valid DepartureDto> departure) {

    // @Size 검증은 생성 뒤에 돌므로 여기서 떼면 "   " 는 빈 문자열이 되어 min=1 에 걸린다. strip 은 전각 공백(U+3000)도 뗀다
    public UpdateMeRequest {
        nickname = nickname == null ? null : nickname.strip();
    }
}
