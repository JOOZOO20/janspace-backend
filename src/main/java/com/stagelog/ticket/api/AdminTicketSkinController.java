package com.stagelog.ticket.api;

import static com.stagelog.common.constant.ApiVersion.V1_BASE;

import com.stagelog.common.dto.ResponseDTO;
import com.stagelog.ticket.application.TicketSkinService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 티켓 스킨 관리. 관리자 권한 규칙은 보안 설정이 /admin/** 에 건다.
 */
@Tag(name = "관리자 티켓 스킨 (Admin Ticket Skin)", description = "티켓 스킨 전체 목록·upsert(종이색·포인트색·글자색·배포 기간·기본 여부)·삭제")
@RestController
@RequestMapping(V1_BASE + "/admin/ticket-skins")
@RequiredArgsConstructor
public class AdminTicketSkinController {

    private final TicketSkinService skinService;

    @Operation(summary = "티켓 스킨 전체 목록", description = "배포 기간과 무관하게 모든 스킨을 ID 순으로 냅니다.")
    @GetMapping
    public ResponseDTO<List<TicketSkinResponse>> list() {
        return ResponseDTO.okWithData(skinService.all().stream().map(TicketSkinResponse::from).toList());
    }

    @Operation(summary = "티켓 스킨 upsert",
            description = "id 가 있으면 갱신(없는 id 는 404 TICKET_SKIN_NOT_FOUND), 없으면 생성합니다. 색은 #RRGGBB. isDefault=true 면 기존 기본 스킨은 내려갑니다.")
    @PutMapping
    public ResponseDTO<TicketSkinResponse> upsert(@RequestBody @Valid TicketSkinUpsertRequest request) {
        return ResponseDTO.okWithData(TicketSkinResponse.from(skinService.upsert(request.toCommand())));
    }

    @Operation(summary = "티켓 스킨 삭제",
            description = "기본 스킨은 400 TICKET_SKIN_DEFAULT, 없으면 404 TICKET_SKIN_NOT_FOUND. 이 스킨을 쓰던 티켓은 기본 스킨(skinId null)이 됩니다.")
    @DeleteMapping("/{id}")
    public ResponseDTO<Void> delete(@Parameter(description = "스킨 ID", example = "2") @PathVariable Long id) {
        skinService.delete(id);
        return ResponseDTO.ok();
    }
}
