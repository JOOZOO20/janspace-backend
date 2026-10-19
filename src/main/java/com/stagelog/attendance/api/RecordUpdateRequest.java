package com.stagelog.attendance.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;

/**
 * 프론트 RecordUpdate 계약 (부분 수정). 필드가 없으면 변경 없음, null 이면 지운다 (Optional: 없음 = null, "x": null = empty).
 * memo 길이는 생성과 같은 500자 (memo 컬럼 VARCHAR(500)). 넘으면 400 INVALID_INPUT.
 */
@Schema(description = "기록 부분 수정 요청. 보낸 필드만 반영한다")
public record RecordUpdateRequest(
        @Schema(description = "감정 코드 1~3개, 순서 = 순위. 소급 별에 넣으면 밝아진다", example = "[\"HAPPY\"]",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<List<String>> emotions,
        @Schema(description = "최고의 곡 ID. null 이면 지운다", example = "5", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<Long> songId,
        @Schema(description = "한 줄 메모 (500자 이하). null 이면 지운다", example = "앙코르에서 울었다", maxLength = 500,
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<@Size(max = 500) String> memo) {
}
