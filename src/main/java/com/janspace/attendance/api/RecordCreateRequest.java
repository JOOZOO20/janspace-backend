package com.janspace.attendance.api;

import com.janspace.attendance.application.RecordInput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 프론트 RecordCreate 계약. 감정 코드 검증(개수·중복·미지 코드)은 유스케이스가 INVALID_EMOTIONS 로 낸다.
 */
@Schema(description = "기록 생성 요청")
public record RecordCreateRequest(
        @Schema(description = "행사 ID", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Long eventId,
        @Schema(description = "감정 코드 1~3개, 순서 = 순위. 소급(isRetro) 이면 빈 배열 허용", example = "[\"HAPPY\", \"OVERWHELMED\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull List<String> emotions,
        @Schema(description = "최고의 곡 ID. 없으면 null", example = "5", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Long songId,
        @Schema(description = "한 줄 메모 (나만 본다). 없으면 null", example = "앙코르에서 울었다", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 500) String memo,
        @Schema(description = "소급 기록([갔어요]) 여부. true 면 감정 0개 허용", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isRetro,
        @Schema(description = "연속 이동(직전 공연에서 바로 왔어요). can-chain 이 true 일 때만", example = "false",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean chainedFromPrevious,
        @Schema(description = "출발지. 없으면 프로필 출발지, 그것도 없으면 409 DEPARTURE_REQUIRED",
                example = "{\"name\":\"서울 노원구\",\"countryCode\":\"KR\",\"lat\":37.654,\"lng\":127.056}", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Valid PlaceDto departure) {

    RecordInput toInput() {
        return new RecordInput(eventId, emotions, songId, memo, isRetro, Boolean.TRUE.equals(chainedFromPrevious),
                departure == null ? null : departure.toInput());
    }
}
