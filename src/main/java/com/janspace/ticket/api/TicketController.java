package com.janspace.ticket.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.common.auth.CurrentUser;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.ticket.application.TicketRecognition;
import com.janspace.ticket.application.TicketService;
import com.janspace.ticket.application.TicketSkinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 티켓북(로그인 필수)과 공개 티켓 스킨 목록. 사진은 받지 않는다: 기기가 OCR 로 읽은 글자 줄만 /tickets/recognize 로 받는다.
 */
@Tag(name = "티켓북 (Ticket)", description = "티켓 인식(기기 OCR 글자 -> 행사 후보)·꽂기·조회·수정·삭제, 티켓 스킨 조회 API")
@RestController
@RequestMapping(V1_BASE)
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final TicketRecognition recognition;
    private final TicketSkinService skinService;

    @Operation(summary = "내 티켓 목록", description = "행사 날짜 내림차순(같은 날은 시작 시각·연번 내림차순). recordId 는 같은 행사의 내 기록입니다.")
    @GetMapping("/tickets")
    public ResponseDTO<List<TicketResponse>> list(@Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(ticketService.mine(user.userId()).stream().map(TicketResponse::from).toList());
    }

    @Operation(summary = "티켓 인식 (기기 OCR 글자 줄 -> 행사 후보)",
            description = "사진이 아니라 기기가 읽은 글자 줄을 받습니다. 글자에서 날짜를 찾아 그 날의 행사를 이름(번역 포함)·시작 시각으로 점수 매겨 냅니다. "
                    + "날짜가 없거나 그 날 행사가 없으면 참석 표시·기록했지만 티켓이 없는 행사를 대신 냅니다(matchStatus). 좌석만 글자에서 뽑습니다. "
                    + "받은 글자는 저장하지 않습니다. 줄 수·길이 초과는 400 INVALID_INPUT.")
    @PostMapping("/tickets/recognize")
    public ResponseDTO<TicketRecognitionResponse> recognize(
            @RequestBody @Valid TicketRecognizeRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(TicketRecognitionResponse.from(recognition.recognize(user.userId(), request.lines())));
    }

    @Operation(summary = "티켓 꽂기",
            description = "사용자가 확인한 행사·좌석·색으로 티켓을 만듭니다. 행사가 없으면 404 EVENT_NOT_FOUND, 같은 행사에 이미 티켓이 있으면 409 TICKET_ALREADY_EXISTS, "
                    + "없는 스킨은 404 TICKET_SKIN_NOT_FOUND. skinId 를 보내지 않으면 오늘 배포 중인 시즌 스킨을 입힙니다.")
    @PostMapping("/tickets")
    public ResponseDTO<TicketResponse> create(
            @RequestBody @Valid TicketCreateRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(TicketResponse.from(ticketService.create(user.userId(), request.eventId(), request.seat(),
                request.accent(), request.skinId())));
    }

    @Operation(summary = "티켓 부분 수정",
            description = "보낸 필드만 반영합니다(키 없음 = 유지, null = 지움). eventId 로 다른 행사로 옮길 수 있습니다(null 은 400, 이미 티켓이 있는 행사면 409). "
                    + "내 티켓이 아니면 404 TICKET_NOT_FOUND.")
    @PatchMapping("/tickets/{id}")
    public ResponseDTO<TicketResponse> update(
            @Parameter(description = "티켓 ID", example = "7") @PathVariable Long id,
            @RequestBody @Valid TicketUpdateRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(TicketResponse.from(ticketService.update(user.userId(), id, request.eventId(), request.seat(),
                request.accent(), request.skinId())));
    }

    @Operation(summary = "티켓 삭제", description = "티켓을 지웁니다(물리 삭제). 내 티켓이 아니면 404 TICKET_NOT_FOUND.")
    @DeleteMapping("/tickets/{id}")
    public ResponseDTO<Void> delete(
            @Parameter(description = "티켓 ID", example = "7") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        ticketService.delete(user.userId(), id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "티켓 스킨 목록 (공개)", description = "기본 스킨과, 오늘(UTC) 배포 기간 안의 시즌 스킨만 ID 순으로 냅니다.")
    @GetMapping("/ticket-skins")
    public ResponseDTO<List<TicketSkinResponse>> skins() {
        return ResponseDTO.okWithData(skinService.listed().stream().map(TicketSkinResponse::from).toList());
    }
}
