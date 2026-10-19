package com.stagelog.photocard.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Optional;

/**
 * PATCH /photocards/{id}. 키가 없으면 유지, null 이면 연결 해제 (Optional: 키 없음 = null, "x": null = empty).
 */
@Schema(description = "포토카드 부분 수정 요청. 보낸 필드만 반영한다")
public record PhotocardUpdateRequest(
        @Schema(description = "붙일 기록(별) ID. null 이면 연결을 끊고, 키를 빼면 그대로 둔다", example = "31",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Optional<Long> linkedRecordId) {
}
