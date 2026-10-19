package com.stagelog.ticket.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 기기(브라우저) OCR 이 티켓 사진에서 읽은 글자 줄. 사진은 보내지 않는다. 서버는 이 글자를 저장하지 않는다.
 */
@Schema(description = "티켓 인식 요청 (기기 OCR 결과 글자 줄)")
public record TicketRecognizeRequest(
        @Schema(description = "인식된 글자 줄, 위에서 아래 순서. 1~200줄, 줄당 200자 이하. 빈 줄은 무시한다",
                example = "[\"스타더스트 클럽 전국투어 [우리들의 은하] - 서울\", \"2026.09.16(수) 19:00\", \"KSPO DOME\", \"1층 A구역 12열 5번\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Size(min = 1, max = 200) List<@NotNull @Size(max = 200) String> lines) {
}
