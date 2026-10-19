package com.stagelog.attendance.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.attendance.application.StatsQuery;
import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.auth.CurrentUser;
import com.stagelog.common.dto.ResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 통계 (로그인 필수).
 */
@Tag(name = "통계 (Stats)", description = "누적 거리와 높이 헤드라인")
@RestController
@RequestMapping(V1_BASE + "/me/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsQuery statsQuery;

    @Operation(summary = "내 통계", description = "누적 거리(km), 기록 수, 마지막 기록 시각, 높이 헤드라인. 순위·백분위는 없습니다.")
    @GetMapping
    public ResponseDTO<StatsResponse> me(@Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(StatsResponse.from(statsQuery.of(user.userId())));
    }
}
