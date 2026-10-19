package com.stagelog.event.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.auth.AuthenticatedUser;
import com.stagelog.common.auth.CurrentUser;
import com.stagelog.common.dto.PagedResponse;
import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.event.application.EventQuery;
import com.stagelog.event.application.EventQuery.Tab;
import com.stagelog.event.application.EventView;
import com.stagelog.event.application.ProposalUseCase;
import com.stagelog.event.application.port.AttendanceSummaryPort;
import com.stagelog.event.application.port.AttendanceSummaryPort.MySummary;
import com.stagelog.event.application.port.PlaylistUrlPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 행사 API. 조회는 누구나, 제안 등록·내 제안·갈래요는 로그인 필수 (SecurityConfig).
 * starCount·my 는 AttendanceSummaryPort(attendance 구현)로 얹는다. 갈래요 엔드포인트가 여기 있는 이유: 응답이 Event 이고
 * attendance 는 event.api 의 DTO 를 import 할 수 없어(ArchUnit 규칙 5) 포트로 attendance 를 부른다.
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
    private final ObjectProvider<AttendanceSummaryPort> attendance;
    // setlist 기능이 제공한다. 빈이 없으면 playlistUrl 은 null
    private final ObjectProvider<PlaylistUrlPort> playlistUrlPort;

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
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size,
            @Parameter(hidden = true) @CurrentUser(required = false) AuthenticatedUser user) {
        var views = eventQuery.page(tab, year, Math.max(page, 0), Math.clamp(size, 1, MAX_SIZE));
        List<EventResponse> content = withSummary(views.getContent(), user);
        var result = new PageImpl<>(content, views.getPageable(), views.getTotalElements());
        log.info("[행사 목록 조회] tab={}, year={}, page={}, count={}", tab, year, page, result.getNumberOfElements());
        return ResponseDTO.okWithData(PagedResponse.from(result));
    }

    @Operation(summary = "행사 상세 조회", description = "행사 ID 로 행사 하나를 조회합니다. 없거나 삭제된 행사는 404 EVENT_NOT_FOUND. 로그인하면 my 에 내 참석 상태가 들어갑니다.")
    @GetMapping("/events/{id}")
    public ResponseDTO<EventResponse> detail(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser(required = false) AuthenticatedUser user) {
        return ResponseDTO.okWithData(withSummary(List.of(eventQuery.view(id)), user).get(0));
    }

    @Operation(summary = "갈래요", description = "행사에 갈래요를 표시합니다. 출발지가 없으면 409 DEPARTURE_REQUIRED. 이미 눌렀거나 기록한 행사면 그대로 둡니다. 응답은 my 가 채워진 행사입니다.")
    @PostMapping("/events/{id}/going")
    public ResponseDTO<EventResponse> going(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        EventView view = eventQuery.view(id);
        MySummary my = attendance.getObject().plan(user.userId(), id);
        return ResponseDTO.okWithData(EventResponse.from(view, playlistUrls(List.of(id)).get(id), starCount(id), my));
    }

    @Operation(summary = "갈래요 취소", description = "갈래요를 취소합니다. 이미 기록(갔어요)한 행사면 409 RECORD_ALREADY_EXISTS. 갈래요가 없으면 그대로 둡니다.")
    @DeleteMapping("/events/{id}/going")
    public ResponseDTO<EventResponse> notGoing(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        EventView view = eventQuery.view(id);
        MySummary my = attendance.getObject().unplan(user.userId(), id);
        return ResponseDTO.okWithData(EventResponse.from(view, playlistUrls(List.of(id)).get(id), starCount(id), my));
    }

    // starCount 는 항상, my 는 로그인 사용자에게만(행이 없으면 NONE). attendance 구현이 없으면 0 / NONE
    private List<EventResponse> withSummary(List<EventView> views, AuthenticatedUser user) {
        List<Long> ids = views.stream().map(EventView::id).toList();
        AttendanceSummaryPort port = attendance.getIfAvailable();
        Map<Long, Long> stars = port == null || ids.isEmpty() ? Map.of() : port.starCounts(ids);
        Map<Long, MySummary> mine = port == null || user == null || ids.isEmpty() ? Map.of() : port.my(user.userId(), ids);
        Map<Long, String> playlists = playlistUrls(ids);
        return views.stream().map(v -> EventResponse.from(v, playlists.get(v.id()), stars.getOrDefault(v.id(), 0L),
                user == null ? null : mine.getOrDefault(v.id(), MySummary.NONE))).toList();
    }

    // 재생목록 URL 은 setlist 구현이 있을 때만 채운다
    private Map<Long, String> playlistUrls(List<Long> eventIds) {
        PlaylistUrlPort port = playlistUrlPort.getIfAvailable();
        return port == null || eventIds.isEmpty() ? Map.of() : port.playlistUrls(eventIds);
    }

    private long starCount(Long eventId) {
        return attendance.getObject().starCounts(List.of(eventId)).getOrDefault(eventId, 0L);
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
