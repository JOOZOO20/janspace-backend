package com.stagelog.attendance.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.attendance.application.AttendanceCommand;
import com.stagelog.attendance.application.AttendanceQuery;
import com.stagelog.attendance.application.AttendanceQuery.ChainCheck;
import com.stagelog.attendance.application.AttendanceQuery.Star;
import com.stagelog.attendance.domain.Attendance;
import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.auth.CurrentUser;
import com.stagelog.common.dto.ResponseDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 기록 API (비공개, 로그인 필수). 갈래요(POST/DELETE /events/{id}/going)는 응답이 Event 라 event 의 EventController 에 있다.
 */
@Slf4j
@Tag(name = "기록 (Record)", description = "갔어요·기록 생성·조회·수정·삭제와 연속 이동 확인 API")
@RestController
@RequestMapping(V1_BASE + "/records")
@RequiredArgsConstructor
public class AttendanceController {

    private final AttendanceCommand attendanceCommand;
    private final AttendanceQuery attendanceQuery;

    @Operation(summary = "내 기록 목록", description = "내 기록(별)을 최신 행사부터 냅니다. cumulativeKm 은 행사 순으로 더한 그 시점의 누적 거리입니다.")
    @GetMapping
    public ResponseDTO<List<StarRecordResponse>> list(@Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        List<StarRecordResponse> out = new ArrayList<>(attendanceQuery.stars(user.userId()).stream().map(StarRecordResponse::from).toList());
        Collections.reverse(out);
        return ResponseDTO.okWithData(out);
    }

    @Operation(summary = "내 기록 하나", description = "내 기록이 아니거나 삭제됐으면 404 RECORD_NOT_FOUND.")
    @GetMapping("/{id}")
    public ResponseDTO<StarRecordResponse> get(
            @Parameter(description = "기록 ID", example = "31") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(StarRecordResponse.from(attendanceQuery.star(user.userId(), id)));
    }

    @Operation(summary = "기록 생성 (갔어요 포함)",
            description = "행사 현지 날짜 당일부터 가능(400 EVENT_NOT_ENDED). 감정 1~3개(소급은 0개 허용, 아니면 400 INVALID_EMOTIONS). "
                    + "유저 x 행사 1건(409 RECORD_ALREADY_EXISTS). 출발지 없으면 409 DEPARTURE_REQUIRED. "
                    + "chainedFromPrevious 는 can-chain 이 true 일 때만(400 CHAIN_NOT_ALLOWED). 곡이 없으면 404 SONG_NOT_FOUND.")
    @PostMapping
    public ResponseDTO<StarRecordResponse> create(
            @RequestBody @Valid RecordCreateRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        Attendance created = attendanceCommand.create(user.userId(), request.toInput());
        return ResponseDTO.okWithData(StarRecordResponse.from(attendanceQuery.star(user.userId(), created.getId())));
    }

    @Operation(summary = "기록 부분 수정", description = "보낸 필드만 반영합니다. 소급 별에 감정을 넣으면 밝아집니다. 감정을 비울 수는 없습니다(400 INVALID_EMOTIONS).")
    @PatchMapping("/{id}")
    public ResponseDTO<StarRecordResponse> update(
            @Parameter(description = "기록 ID", example = "31") @PathVariable Long id,
            @RequestBody @Valid RecordUpdateRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        attendanceCommand.update(user.userId(), id, request.emotions(), request.songId(), request.memo());
        return ResponseDTO.okWithData(StarRecordResponse.from(attendanceQuery.star(user.userId(), id)));
    }

    @Operation(summary = "기록 삭제", description = "소프트 삭제합니다. 같은 행사에 다시 기록하면 같은 행이 되살아납니다.")
    @DeleteMapping("/{id}")
    public ResponseDTO<Void> delete(
            @Parameter(description = "기록 ID", example = "31") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        attendanceCommand.delete(user.userId(), id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "연속 이동 가능 여부", description = "이 행사 직전에 간 기록이 0~5일 전 다른 도시이고 두 공연장 좌표가 있으면 eligible=true.")
    @GetMapping("/can-chain")
    public ResponseDTO<CanChainResponse> canChain(
            @Parameter(description = "기록하려는 행사 ID", example = "12") @RequestParam Long eventId,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        ChainCheck check = attendanceQuery.canChain(user.userId(), eventId);
        return ResponseDTO.okWithData(new CanChainResponse(check.eligible(), check.previousEventId()));
    }
}
