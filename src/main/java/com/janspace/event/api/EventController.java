package com.janspace.event.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.common.auth.CurrentUser;
import com.janspace.common.dto.PagedResponse;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventQuery.Tab;
import com.janspace.event.application.ProposalUseCase;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 행사 API. 조회는 누구나, 제안 등록·내 제안은 로그인 필수 (SecurityConfig).
 */
@Slf4j
@Tag(name = "행사 (Event)", description = "행사 목록·상세 조회와 팬 제안 API")
@RestController
@RequestMapping(V1_BASE)
@RequiredArgsConstructor
public class EventController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private final EventQuery eventQuery;
    private final ProposalUseCase proposalUseCase;

    @Operation(summary = "행사 목록 조회",
            description = "UPCOMING 은 오늘(Asia/Seoul) 이후를 날짜 오름차순, PAST 는 오늘 이전을 내림차순으로 냅니다. year 로 연도를 좁힐 수 있습니다. 취소 행사도 status 와 함께 포함됩니다.")
    @GetMapping("/events")
    public ResponseDTO<PagedResponse<EventResponse>> list(
            @Parameter(description = "탭 (UPCOMING / PAST)", example = "UPCOMING")
            @RequestParam(defaultValue = "UPCOMING") Tab tab,
            @Parameter(description = "연도 필터 (event_date 기준). 생략 가능", example = "2026")
            @RequestParam(required = false) Integer year,
            @Parameter(description = "페이지 번호 (0부터)", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지 크기 (1~100, 기본 20)", example = "20")
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        var result = eventQuery.page(tab, year, Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE)).map(EventResponse::from);
        log.info("[행사 목록 조회] tab={}, year={}, page={}, count={}", tab, year, page, result.getNumberOfElements());
        return ResponseDTO.okWithData(PagedResponse.from(result));
    }

    @Operation(summary = "행사 상세 조회", description = "행사 ID 로 행사 하나를 조회합니다. 없거나 삭제된 행사는 404 EVENT_NOT_FOUND.")
    @GetMapping("/events/{id}")
    public ResponseDTO<EventResponse> detail(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id) {
        return ResponseDTO.okWithData(EventResponse.from(eventQuery.view(id)));
    }

    @Operation(summary = "팬 제안 등록", description = "로그인한 사용자가 행사 정보 수정 제안을 남깁니다. 관리자가 검토해 APPLIED/HELD 로 처리합니다.")
    @PostMapping("/events/{id}/suggestions")
    public ResponseDTO<SuggestionResponse> propose(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @RequestBody @Valid ProposalRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(SuggestionResponse.from(proposalUseCase.create(id, user.userId(), request.text())));
    }

    @Operation(summary = "내 제안 목록", description = "로그인한 사용자가 남긴 제안을 최신순으로 조회합니다.")
    @GetMapping("/me/suggestions")
    public ResponseDTO<List<SuggestionResponse>> mine(@Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(proposalUseCase.mine(user.userId()).stream().map(SuggestionResponse::from).toList());
    }
}
