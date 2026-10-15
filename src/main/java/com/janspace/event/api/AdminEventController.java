package com.janspace.event.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.locale.SupportedLocale;
import com.janspace.event.api.AdminRequests.DiscoveryAcceptRequest;
import com.janspace.event.api.AdminRequests.PosterResponse;
import com.janspace.event.api.AdminRequests.SuggestionStatusRequest;
import com.janspace.event.api.AdminRequests.TranslationRequest;
import com.janspace.event.api.AdminRequests.UnlockRequest;
import com.janspace.event.api.AdminRequests.VenueUpdateRequest;
import com.janspace.event.application.CandidateUseCase;
import com.janspace.event.application.DiscoveryUseCase;
import com.janspace.event.application.EventCommand;
import com.janspace.event.application.EventQuery;
import com.janspace.event.application.EventSourceQuery;
import com.janspace.event.application.EventView;
import com.janspace.event.application.ProposalUseCase;
import com.janspace.event.application.SourceChangeReviewUseCase;
import com.janspace.event.application.VenueCommand;
import com.janspace.event.application.VenueCommand.VenueUpdate;
import com.janspace.event.domain.EventLockableField;
import com.janspace.event.domain.TranslationField;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 행사 API. /admin/** 은 SecurityConfig 가 ADMIN 권한을 요구한다.
 */
@Slf4j
@Tag(name = "관리자 행사 (Admin Event)", description = "행사 생성·수정·잠금 해제·출처 검토·제안 처리·번역·포스터·공연장 수정 API")
@RestController
@RequestMapping(V1_BASE + "/admin")
@RequiredArgsConstructor
public class AdminEventController {

    private final EventCommand eventCommand;
    private final EventQuery eventQuery;
    private final EventSourceQuery eventSourceQuery;
    private final CandidateUseCase candidateUseCase;
    private final SourceChangeReviewUseCase reviewUseCase;
    private final ProposalUseCase proposalUseCase;
    private final DiscoveryUseCase discoveryUseCase;
    private final VenueCommand venueCommand;

    @Operation(summary = "행사 생성",
            description = "관리자가 행사를 만듭니다. name·kind·date 는 필수. 준 필드는 전부 잠겨 이후 출처 동기화가 덮어쓰지 않습니다. 공연장 좌표가 없으면 지오코딩하고, 번역이 없으면 기계 초안을 만듭니다.")
    @PostMapping("/events")
    public ResponseDTO<EventResponse> create(@RequestBody @Valid AdminEventInput input) {
        Long id = eventCommand.create(input.toInput());
        return ResponseDTO.okWithData(EventResponse.from(eventQuery.view(id)));
    }

    @Operation(summary = "행사 부분 수정", description = "준 필드만 반영합니다(null 은 변경 없음). 각 필드는 관리자 값으로 잠깁니다.")
    @PatchMapping("/events/{id}")
    public ResponseDTO<EventResponse> update(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @RequestBody @Valid AdminEventInput input) {
        eventCommand.update(id, input.toInput());
        return ResponseDTO.okWithData(EventResponse.from(eventQuery.view(id)));
    }

    @Operation(summary = "필드 잠금 해제 (출처 값 따르기)", description = "잠금을 풀고 즉시 출처 접기를 다시 실행합니다. 민감 필드의 값→값 변경은 확인 큐로 갑니다.")
    @PostMapping("/events/{id}/unlock")
    public ResponseDTO<EventResponse> unlock(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @RequestBody @Valid UnlockRequest request) {
        EventLockableField field;
        try {
            field = EventLockableField.valueOf(request.field().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCode.EVENT_FIELD_NOT_LOCKABLE, "잠글 수 없는 필드입니다: " + request.field());
        }
        eventCommand.unlock(id, field);
        return ResponseDTO.okWithData(EventResponse.from(eventQuery.view(id)));
    }

    @Operation(summary = "행사 출처 현황", description = "연결된 출처, 매칭 후보, 대기 중 변경 요청, 종료 시각 초안을 조회합니다.")
    @GetMapping("/events/{id}/sources")
    public ResponseDTO<EventSourcesResponse> sources(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id) {
        EventView view = eventQuery.view(id);
        return ResponseDTO.okWithData(EventSourcesResponse.from(eventSourceQuery.of(id), view.timezone()));
    }

    @Operation(summary = "매칭 후보 선택", description = "후보 하나를 출처로 확정하고 같은 제공자의 나머지 후보를 삭제한 뒤 접기를 실행합니다.")
    @PostMapping("/events/{id}/candidates/{candidateId}/select")
    public ResponseDTO<Void> selectCandidate(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @Parameter(description = "후보 ID", example = "5") @PathVariable Long candidateId) {
        candidateUseCase.select(id, candidateId);
        return ResponseDTO.ok();
    }

    @Operation(summary = "출처 변경 요청 목록", description = "민감 필드(날짜·시각·공연장) 변경 확인 큐를 감지 시각 최신순으로 조회합니다.")
    @GetMapping("/source-changes")
    public ResponseDTO<List<SourceChangeResponse>> sourceChanges(
            @Parameter(description = "상태 (PENDING / APPLIED / REJECTED)", example = "PENDING")
            @RequestParam(defaultValue = "PENDING") com.janspace.event.domain.SourceChangeStatus status) {
        return ResponseDTO.okWithData(reviewUseCase.list(status).stream().map(SourceChangeResponse::from).toList());
    }

    @Operation(summary = "출처 변경 승인", description = "값을 적용하고 UTC 시각·회차 키를 함께 갱신한 뒤 EventScheduleChanged/EventVenueChanged 를 발행합니다.")
    @PostMapping("/source-changes/{id}/approve")
    public ResponseDTO<Void> approve(@Parameter(description = "변경 요청 ID", example = "3") @PathVariable Long id) {
        reviewUseCase.approve(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "출처 변경 거절", description = "거절된 값은 REJECTED 행이 남아 있는 동안 다음 접기에서 무시됩니다.")
    @PostMapping("/source-changes/{id}/reject")
    public ResponseDTO<Void> reject(@Parameter(description = "변경 요청 ID", example = "3") @PathVariable Long id) {
        reviewUseCase.reject(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "제안 목록 (팬 제안 + 발견 행사)", description = "팬 제안과 제공자 발견 행사를 합쳐 최신순으로 냅니다 (프론트 AdminSuggestion).")
    @GetMapping("/suggestions")
    public ResponseDTO<List<AdminSuggestionResponse>> suggestions() {
        List<AdminSuggestionResponse> merged = new ArrayList<>();
        proposalUseCase.all().forEach(p -> merged.add(AdminSuggestionResponse.fan(p)));
        discoveryUseCase.all().forEach(d -> merged.add(AdminSuggestionResponse.discovery(d)));
        merged.sort(Comparator.comparing(AdminSuggestionResponse::createdAt).reversed());
        return ResponseDTO.okWithData(merged);
    }

    @Operation(summary = "팬 제안 상태 변경", description = "FAN 제안을 APPLIED 또는 HELD 로 바꿉니다.")
    @PostMapping("/suggestions/{id}")
    public ResponseDTO<Void> setSuggestionStatus(
            @Parameter(description = "제안 ID", example = "7") @PathVariable Long id,
            @RequestBody @Valid SuggestionStatusRequest request) {
        proposalUseCase.setStatus(id, request.status());
        return ResponseDTO.ok();
    }

    @Operation(summary = "발견 행사 수락", description = "eventId 가 없으면 초안으로 새 행사를 만들고, 있으면 그 행사에 출처로 연결합니다. 결과 행사를 돌려줍니다.")
    @PostMapping("/discoveries/{id}/accept")
    public ResponseDTO<EventResponse> acceptDiscovery(
            @Parameter(description = "발견 ID", example = "9") @PathVariable Long id,
            @RequestBody(required = false) DiscoveryAcceptRequest request) {
        Long eventId = discoveryUseCase.accept(id, request == null ? null : request.eventId());
        return ResponseDTO.okWithData(EventResponse.from(eventQuery.view(eventId)));
    }

    @Operation(summary = "발견 행사 무시", description = "발견 행사를 DISMISSED 로 닫습니다.")
    @PostMapping("/discoveries/{id}/dismiss")
    public ResponseDTO<Void> dismissDiscovery(@Parameter(description = "발견 ID", example = "9") @PathVariable Long id) {
        discoveryUseCase.dismiss(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "행사 번역 등록·수정", description = "필드(NAME/DESCRIPTION)·로케일당 한 행을 관리자 번역으로 upsert 합니다.")
    @PutMapping("/events/{id}/translations")
    public ResponseDTO<Void> upsertTranslation(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @RequestBody @Valid TranslationRequest request) {
        TranslationField field;
        try {
            field = TranslationField.valueOf(request.field().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "번역 필드는 NAME 또는 DESCRIPTION 입니다.");
        }
        SupportedLocale locale = SupportedLocale.fromTag(request.locale())
                .orElseThrow(() -> new BadRequestException(ErrorCode.INVALID_INPUT, "지원하지 않는 로케일입니다: " + request.locale()));
        eventCommand.upsertTranslation(id, field, locale, request.text(), request.reviewed());
        return ResponseDTO.ok();
    }

    @Operation(summary = "포스터 업로드", description = "이미지(jpeg/png/webp)를 S3 에 올리고 POSTER 필드를 잠급니다. 공개 URL 을 돌려줍니다.")
    @PutMapping(value = "/events/{id}/poster", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<PosterResponse> uploadPoster(
            @Parameter(description = "행사 ID", example = "12") @PathVariable Long id,
            @Parameter(description = "포스터 이미지 파일 (jpeg/png/webp)", example = "poster.jpg") @RequestPart("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.POSTER_INVALID, "파일을 읽을 수 없습니다.");
        }
        return ResponseDTO.okWithData(new PosterResponse(eventCommand.uploadPoster(id, bytes, file.getContentType())));
    }

    @Operation(summary = "공연장 수정", description = "준 필드만 반영하며 각 필드는 잠깁니다. 좌표 변경은 VenueCoordinatesChanged, 시간대 변경은 잠기지 않은 행사에 전파됩니다.")
    @PatchMapping("/venues/{id}")
    public ResponseDTO<Void> updateVenue(
            @Parameter(description = "공연장 ID", example = "3") @PathVariable Long id,
            @RequestBody @Valid VenueUpdateRequest request) {
        venueCommand.adminUpdate(id, new VenueUpdate(request.name(), request.city(),
                request.location() == null ? null : request.location().toPoint(), request.timezone()));
        return ResponseDTO.ok();
    }
}
