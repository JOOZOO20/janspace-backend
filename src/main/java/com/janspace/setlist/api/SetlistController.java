package com.janspace.setlist.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.common.auth.CurrentUser;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.setlist.api.SetlistRequests.EditRequest;
import com.janspace.setlist.api.SetlistRequests.RevertRequest;
import com.janspace.setlist.api.SetlistResponses.RevisionResponse;
import com.janspace.setlist.api.SetlistResponses.SetlistResponse;
import com.janspace.setlist.application.EditSetlistUseCase;
import com.janspace.setlist.application.RevertSetlistUseCase;
import com.janspace.setlist.application.SetlistQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 셋리 위키 API. 읽기는 누구나, 편집·되돌리기는 로그인 + 동의 (SecurityConfig·ConsentInterceptor). 역사는 익명이다.
 */
@Slf4j
@Tag(name = "셋리 위키 (Setlist)", description = "행사별 셋리 조회·편집·역사·되돌리기 API")
@RestController
@RequestMapping(V1_BASE + "/events/{id}/setlist")
@RequiredArgsConstructor
public class SetlistController {

    private final SetlistQuery setlistQuery;
    private final EditSetlistUseCase editUseCase;
    private final RevertSetlistUseCase revertUseCase;

    @Operation(summary = "셋리 조회", description = "행사의 현재 셋리를 돌려줍니다. 아직 아무도 편집하지 않았으면 version 0 의 빈 목록입니다. 없는 행사는 404 EVENT_NOT_FOUND.")
    @GetMapping
    public ResponseDTO<SetlistResponse> get(@Parameter(description = "행사 ID", example = "12") @PathVariable Long id) {
        return ResponseDTO.okWithData(SetlistResponse.from(setlistQuery.page(id)));
    }

    @Operation(summary = "셋리 편집", description = "항목 전체를 교체하고 FAN 리비전을 남깁니다. 계정당 하루(UTC) 편집 한도를 넘으면 429 SETLIST_EDIT_LIMIT, version 이 현재와 다르면 409 SETLIST_VERSION_CONFLICT, 없는 곡은 404 SONG_NOT_FOUND.")
    @PutMapping
    public ResponseDTO<SetlistResponse> edit(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @RequestBody @Valid EditRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        log.info("[셋리 편집 요청] eventId={}, userId={}, 곡수={}, version={}", id, user.userId(), request.entries().size(), request.version());
        editUseCase.edit(id, user.userId(), request.songIdsInOrder(), request.version());
        return ResponseDTO.okWithData(SetlistResponse.from(setlistQuery.page(id)));
    }

    @Operation(summary = "셋리 역사", description = "리비전을 최신순으로 돌려줍니다. 편집자 정보는 없습니다.")
    @GetMapping("/history")
    public ResponseDTO<List<RevisionResponse>> history(@Parameter(description = "행사 ID", example = "12") @PathVariable Long id) {
        return ResponseDTO.okWithData(setlistQuery.history(id).stream().map(RevisionResponse::from).toList());
    }

    @Operation(summary = "되돌리기", description = "지정 버전의 스냅샷으로 셋리를 되돌리고 REVERT 리비전을 남깁니다. 편집 한도에 셉니다. 없는 버전은 404 REVISION_NOT_FOUND.")
    @PostMapping("/revert")
    public ResponseDTO<SetlistResponse> revert(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @RequestBody @Valid RevertRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        log.info("[셋리 되돌리기 요청] eventId={}, userId={}, version={}", id, user.userId(), request.version());
        revertUseCase.revert(id, user.userId(), request.version());
        return ResponseDTO.okWithData(SetlistResponse.from(setlistQuery.page(id)));
    }
}
