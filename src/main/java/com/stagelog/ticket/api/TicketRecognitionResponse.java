package com.stagelog.ticket.api;

import com.stagelog.ticket.application.TicketRecognition;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * 인식 결과. 공연명·공연장·시각은 후보 행사의 값이고 글자에서 따로 뽑은 것은 좌석뿐이다. 사용자가 후보를 고르고 좌석을 고친 뒤 POST /tickets 로 꽂는다.
 */
@Schema(description = "티켓 인식 결과")
public record TicketRecognitionResponse(
        @Schema(description = "대조 결과. MATCHED = 글자의 날짜에 행사가 있어 이름·시각 점수순 후보, "
                + "NO_DATE = 날짜를 못 읽음, NO_EVENT_ON_DATE = 읽은 날짜에 행사가 없음. 뒤의 둘은 참석 표시·기록했지만 티켓이 없는 행사를 오늘에 가까운 순으로 낸다",
                example = "MATCHED", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MATCHED", "NO_DATE", "NO_EVENT_ON_DATE"})
        String matchStatus,
        @Schema(description = "글자에서 읽은 날짜 후보 (최대 10개, 해석이 여럿이면 모두)", example = "[\"2026-09-16\"]", requiredMode = Schema.RequiredMode.REQUIRED)
        List<LocalDate> detectedDates,
        @Schema(description = "글자에서 뽑은 좌석. 못 찾으면 null", example = "1층 A구역 12열 5번", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String seat,
        @Schema(description = "후보 행사 (최대 20개, 앞이 가장 유력)",
                example = "[{\"event\":{\"id\":12,\"name\":{\"original\":\"스타더스트 클럽 전국투어 - 서울\"},\"date\":\"2026-09-16\",\"startTime\":\"19:00\",\"timezone\":\"Asia/Seoul\",\"venue\":{\"name\":{\"original\":\"KSPO DOME\",\"ja\":\"KSPOドーム\"},\"city\":{\"original\":\"서울\"}}},\"score\":100,\"recordId\":31}]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<Candidate> candidates) {

    @Schema(description = "후보 행사")
    public record Candidate(
            @Schema(description = "행사",
                    example = "{\"id\":12,\"name\":{\"original\":\"스타더스트 클럽 전국투어 - 서울\"},\"date\":\"2026-09-16\",\"startTime\":\"19:00\",\"timezone\":\"Asia/Seoul\",\"venue\":{\"name\":{\"original\":\"KSPO DOME\",\"ja\":\"KSPOドーム\"},\"city\":{\"original\":\"서울\"}}}",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            TicketEventResponse event,
            @Schema(description = "대조 점수 0~100 (이름 일치 80 + 시작 시각 일치 20). 후보끼리 순서 비교용. 대체 후보는 0", example = "100",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            int score,
            @Schema(description = "이 행사의 내 기록 ID. 없으면 null (꽂은 뒤 소급 기록으로 이어 갈 수 있다)", example = "31",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Long recordId,
            @Schema(description = "이 행사에 이미 꽂은 내 티켓 ID. 없으면 null (있으면 새로 꽂을 수 없다, 409)", example = "7",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Long ticketId) {
    }

    static TicketRecognitionResponse from(TicketRecognition.Result result) {
        return new TicketRecognitionResponse(result.status().name(), result.detectedDates(), result.seat(),
                result.candidates().stream().map(c -> new Candidate(TicketEventResponse.from(c.event()), c.score(), c.recordId(), c.ticketId()))
                        .toList());
    }
}
