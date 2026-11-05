package com.stagelog.setlist.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.setlist.application.YouTubeQuota;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 유튜브 Quota 조회. ADMIN 권한 규칙은 SecurityConfig 의 /admin/** 이다.
 */
@Tag(name = "관리자 유튜브 Quota (Admin YouTube Quota)", description = "YouTube Data API Quota 원장 조회")
@RestController
@RequestMapping(V1_BASE + "/admin/youtube")
@RequiredArgsConstructor
public class AdminYouTubeQuotaController {

    private final YouTubeQuota quota;

    @Operation(summary = "유튜브 Quota 사용 현황", description = "태평양 날짜 하루의 bucket(AUTO·MANUAL)별 한도·사용·예약과 행사별 사용량 상위 10개를 돌려줍니다. date 를 빼면 오늘(DB 시계 기준 태평양 날짜).")
    @GetMapping("/quota")
    public ResponseDTO<YouTubeQuotaResponse> quota(
            @Parameter(description = "조회할 태평양 날짜 (yyyy-MM-dd)", example = "2026-11-05")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseDTO.okWithData(YouTubeQuotaResponse.from(quota.usage(date == null ? quota.today() : date)));
    }
}
