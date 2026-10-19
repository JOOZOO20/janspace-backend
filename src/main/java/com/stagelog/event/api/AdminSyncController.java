package com.stagelog.event.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.common.exception.ApplicationException;
import com.stagelog.common.exception.ErrorCode;
import com.stagelog.common.exception.predefined.BadRequestException;
import com.stagelog.event.application.sync.KopisEnrichment;
import com.stagelog.event.application.sync.NaverCalendarSync;
import com.stagelog.event.application.sync.SetlistFmEventSync;
import com.stagelog.event.application.sync.SyncRunner.SyncResult;
import com.stagelog.event.application.sync.TicketmasterSync;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 수동 동기화 트리거. 동기적으로 실행하고 요약을 돌려준다. 제공자 실패는 sync_runs 에 FAILED 로 남고 502 로 응답한다.
 */
@Slf4j
@Tag(name = "관리자 동기화 (Admin Sync)", description = "네이버 캘린더·KOPIS·Ticketmaster·setlist.fm 행사 동기화 수동 실행 API")
@RestController
@RequestMapping(V1_BASE + "/admin/sync")
@RequiredArgsConstructor
public class AdminSyncController {

    private final NaverCalendarSync naverCalendarSync;
    private final KopisEnrichment kopisEnrichment;
    private final TicketmasterSync ticketmasterSync;
    private final SetlistFmEventSync setlistFmEventSync;

    @Schema(description = "동기화 실행 결과")
    public record SyncTriggerResponse(
            @Schema(description = "동기화 출처", example = "NAVER", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"NAVER", "KOPIS", "TICKETMASTER", "SETLISTFM"})
            String source,
            @Schema(description = "실행 상태", example = "OK", requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"OK", "FAILED"})
            String status,
            @Schema(description = "실행 요약 (sync_runs.summary 와 같다)", example = "네이버 캘린더 2026-09-17~2027-01-22: 일정 5건, 생성 3, 갱신 0, 이동 제안 0, 취소 의심 0, 발견 0",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String summary) {
    }

    @Operation(summary = "행사 동기화 수동 실행",
            description = "지정한 제공자의 동기화를 지금 동기적으로 실행합니다. 실행 이력은 sync_runs 에 남고, 제공자 호출 실패는 502 SYNC_PROVIDER_ERROR 로 응답합니다.")
    @PostMapping("/{source}")
    public ResponseDTO<SyncTriggerResponse> trigger(
            @Parameter(description = "동기화 출처 (naver | kopis | ticketmaster | setlistfm)", example = "naver")
            @PathVariable String source) {
        log.info("[관리자 동기화 트리거] source={}", source);
        SyncResult result = switch (source.toLowerCase()) {
            case "naver" -> naverCalendarSync.sync();
            case "kopis" -> kopisEnrichment.enrich();
            case "ticketmaster" -> ticketmasterSync.sync();
            case "setlistfm" -> setlistFmEventSync.sync();
            default -> throw new BadRequestException(ErrorCode.SYNC_SOURCE_UNSUPPORTED, "지원하지 않는 출처입니다: " + source);
        };
        if (result.failed()) {
            throw new ApplicationException(ErrorCode.SYNC_PROVIDER_ERROR, result.summary());
        }
        return ResponseDTO.okWithData(new SyncTriggerResponse(result.source(), result.ok() ? "OK" : "FAILED", result.summary()));
    }
}
