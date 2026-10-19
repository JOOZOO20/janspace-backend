package com.stagelog.setlist.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.setlist.api.SetlistRequests.LinkRequest;
import com.stagelog.setlist.api.SetlistRequests.OrderConfirmedRequest;
import com.stagelog.setlist.api.SetlistResponses.AuthorizeUrlResponse;
import com.stagelog.setlist.api.SetlistResponses.MergeResponse;
import com.stagelog.setlist.api.SetlistResponses.PlaylistResponse;
import com.stagelog.setlist.api.SetlistResponses.SetlistResponse;
import com.stagelog.setlist.api.SetlistResponses.UnmatchedSongResponse;
import com.stagelog.setlist.application.MergeSetlistFmUseCase;
import com.stagelog.setlist.application.OrderConfirmationUseCase;
import com.stagelog.setlist.application.SetlistQuery;
import com.stagelog.setlist.application.SyncPlaylistUseCase;
import com.stagelog.setlist.application.UnmatchedUseCase;
import com.stagelog.setlist.application.YouTubeAuthUseCase;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 셋리 API: 미매칭 곡명 큐, 순서 확정 토글, 수동 병합·재생목록 동기화, 유튜브 채널 OAuth 연결.
 * ADMIN 권한 규칙은 SecurityConfig 의 /admin/** 이며, OAuth 콜백만 PublicEndpoints 로 열려 있다 (state 로 보호).
 */
@Slf4j
@Tag(name = "관리자 셋리 (Admin Setlist)", description = "미매칭 곡명 연결, 순서 확정, 수동 병합, 재생목록, 유튜브 OAuth")
@RestController
@RequestMapping(V1_BASE + "/admin")
@RequiredArgsConstructor
public class AdminSetlistController {

    private final UnmatchedUseCase unmatchedUseCase;
    private final OrderConfirmationUseCase orderConfirmation;
    private final MergeSetlistFmUseCase mergeUseCase;
    private final SyncPlaylistUseCase syncPlaylist;
    private final YouTubeAuthUseCase youTubeAuth;
    private final SetlistQuery setlistQuery;

    @Operation(summary = "미매칭 곡명 목록", description = "병합 때 별칭·제목으로 연결되지 않은 setlist.fm 곡명을 최신순으로 돌려줍니다.")
    @GetMapping("/songs/unmatched")
    public ResponseDTO<List<UnmatchedSongResponse>> unmatched() {
        return ResponseDTO.okWithData(unmatchedUseCase.list().stream().map(UnmatchedSongResponse::from).toList());
    }

    @Operation(summary = "미매칭 곡명 연결", description = "곡명을 지정 곡의 별칭으로 등록하고 그 행사의 병합을 다시 돌립니다. 없는 미매칭은 404 UNMATCHED_NAME_NOT_FOUND, 없는 곡은 404 SONG_NOT_FOUND, 다른 곡의 별칭이면 409 CONFLICT.")
    @PostMapping("/songs/unmatched/{id}")
    public ResponseDTO<Void> link(
            @Parameter(description = "미매칭 ID", example = "5") @PathVariable Long id,
            @RequestBody @Valid LinkRequest request) {
        log.info("[관리자 미매칭 연결 요청] unmatchedId={}, songId={}", id, request.songId());
        unmatchedUseCase.link(id, request.songId());
        return ResponseDTO.ok();
    }

    @Operation(summary = "순서 확정 토글", description = "셋리의 order_confirmed 를 바꿉니다. 병합 여부(merged_at)와 독립입니다.")
    @PutMapping("/events/{id}/setlist/order-confirmed")
    public ResponseDTO<SetlistResponse> orderConfirmed(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @RequestBody @Valid OrderConfirmedRequest request) {
        orderConfirmation.setConfirmed(id, request.confirmed());
        return ResponseDTO.okWithData(SetlistResponse.from(setlistQuery.page(id)));
    }

    @Operation(summary = "수동 병합", description = "행사에 연결된 setlist.fm 셋리를 지금 병합합니다. 연결이 없거나 셋리가 아직 없으면 changed=false. 없는 행사는 404 EVENT_NOT_FOUND.")
    @PostMapping("/events/{id}/setlist/merge")
    public ResponseDTO<MergeResponse> merge(@Parameter(description = "행사 ID", example = "12") @PathVariable Long id) {
        log.info("[관리자 수동 병합 요청] eventId={}", id);
        return ResponseDTO.okWithData(new MergeResponse(mergeUseCase.merge(id)));
    }

    @Operation(summary = "재생목록 생성·동기화", description = "유튜브 재생목록을 만들거나 현재 위키 순서로 맞춥니다. 채널 미연결이면 409 YOUTUBE_NOT_CONNECTED, API 실패면 502 YOUTUBE_API_ERROR.")
    @PostMapping("/events/{id}/setlist/playlist")
    public ResponseDTO<PlaylistResponse> playlist(@Parameter(description = "행사 ID", example = "12") @PathVariable Long id) {
        log.info("[관리자 재생목록 동기화 요청] eventId={}", id);
        String playlistId = syncPlaylist.syncOrThrow(id);
        return ResponseDTO.okWithData(new PlaylistResponse(playlistId, SetlistQuery.PLAYLIST_URL_PREFIX + playlistId));
    }

    @Operation(summary = "유튜브 OAuth 시작", description = "Stagelog 채널 계정으로 동의할 구글 인가 URL 을 돌려줍니다. state 는 10분 유효합니다.")
    @GetMapping("/youtube/authorize")
    public ResponseDTO<AuthorizeUrlResponse> authorize() {
        return ResponseDTO.okWithData(new AuthorizeUrlResponse(youTubeAuth.authorizeUrl()));
    }

    @Operation(summary = "유튜브 OAuth 콜백", description = "구글이 리다이렉트하는 경로. 코드를 리프레시 토큰으로 바꿔 저장합니다. state 가 틀리면 400.")
    @GetMapping("/youtube/callback")
    public ResponseDTO<Void> callback(
            @Parameter(description = "구글 인가 코드", example = "4/0AX...") @RequestParam(required = false) String code,
            @Parameter(description = "인가 시작 때 발급한 state", example = "abc123") @RequestParam(required = false) String state) {
        youTubeAuth.handleCallback(code, state);
        return ResponseDTO.okWithMessageAndData("유튜브 채널이 연결되었습니다.", null);
    }
}
