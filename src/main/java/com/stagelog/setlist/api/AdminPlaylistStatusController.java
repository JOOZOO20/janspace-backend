package com.stagelog.setlist.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.setlist.application.PlaylistSyncStatusQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 재생목록 동기화 진단 조회. ADMIN 과 MCP_READER 서비스 계정이 부른다 (SecurityConfig). 읽기 전용이며 YouTube 를 호출하지 않는다.
 */
@Slf4j
@Tag(name = "관리자 재생목록 진단 (Admin Playlist Diagnosis)", description = "재생목록 동기화 상태 (MCP 진단 Tool 근거)")
@RestController
@RequestMapping(V1_BASE + "/admin/events")
@RequiredArgsConstructor
public class AdminPlaylistStatusController {

    private final PlaylistSyncStatusQuery statusQuery;

    @Operation(summary = "재생목록 동기화 상태",
            description = "DB 기록 기준으로 마지막 동기화 실행(runId·상태·반영/건너뜀 수), 미룸 표시, 채널 연결 상태(invalid_at), 현재 셋리 영상 수와의 차이, "
                    + "마지막 실행 뒤 셋리 변경(리비전 ID), 영상 ID 가 없는 곡, 오늘 이 행사가 쓴 Quota 를 돌려줍니다. YouTube 를 실시간으로 조회하지 않습니다. "
                    + "없는 행사는 404 EVENT_NOT_FOUND.")
    @GetMapping("/{id}/setlist/playlist-status")
    public ResponseDTO<PlaylistStatusResponse> status(@Parameter(description = "행사 ID", example = "12") @PathVariable Long id) {
        PlaylistStatusResponse response = PlaylistStatusResponse.from(statusQuery.status(id));
        log.info("[재생목록 동기화 상태 조회] eventId={}, lastRunId={}, gap={}", id,
                response.lastRun() == null ? null : response.lastRun().runId(), response.videoCountGap());
        return ResponseDTO.okWithData(response);
    }
}
