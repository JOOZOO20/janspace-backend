package com.janspace.photocard.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.common.auth.CurrentUser;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.photocard.application.CreatePhotocardUseCase;
import com.janspace.photocard.application.DeletePhotocardUseCase;
import com.janspace.photocard.application.FrameQuery;
import com.janspace.photocard.application.PhotocardQuery;
import com.janspace.photocard.application.StickerQuery;
import com.janspace.photocard.application.UpdatePhotocardUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 포토카드·네컷 바인더(로그인 필수)와 공개 스티커 세트·프레임 목록.
 */
@Slf4j
@Tag(name = "포토카드 (Photocard)", description = "포토카드·네컷 생성·바인더 조회·연결 수정·삭제, 이번 달 남은 수, 스티커 세트·네컷 프레임 조회 API")
@RestController
@RequestMapping(V1_BASE)
@RequiredArgsConstructor
public class PhotocardController {

    private final CreatePhotocardUseCase createPhotocard;
    private final UpdatePhotocardUseCase updatePhotocard;
    private final DeletePhotocardUseCase deletePhotocard;
    private final PhotocardQuery photocardQuery;
    private final StickerQuery stickerQuery;
    private final FrameQuery frameQuery;

    @Operation(summary = "내 포토카드 목록", description = "내 바인더의 포토카드(카드·네컷)를 최신순으로 냅니다.")
    @GetMapping("/photocards")
    public ResponseDTO<List<PhotocardResponse>> list(@Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(photocardQuery.mine(user.userId()).stream().map(PhotocardResponse::from).toList());
    }

    @Operation(summary = "이번 달 남은 포토카드 수",
            description = "UTC 월 기준으로 카드와 네컷을 합산한 월 제한, 남은 장 수, 다시 차는 시각을 냅니다. 지운 장은 세지 않습니다.")
    @GetMapping("/photocards/quota")
    public ResponseDTO<PhotocardQuotaResponse> quota(@Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(PhotocardQuotaResponse.from(photocardQuery.quota(user.userId())));
    }

    @Operation(summary = "포토카드 생성",
            description = "multipart: image 파트(완성 이미지 PNG/JPEG. CARD 는 4:5·긴 변 1350px·5MB 이하, FOURCUT 은 1:3·긴 변 3240px·9MB 이하, "
                    + "아니면 400 PHOTOCARD_IMAGE_INVALID) + meta 파트(Content-Type application/json). 카드·네컷 합산 월 10장 초과는 429 PHOTOCARD_LIMIT, "
                    + "내 기록이 아닌 linkedRecordId 는 404 RECORD_NOT_FOUND, 이미 다른 카드가 붙은 기록은 409 PHOTOCARD_ALREADY_LINKED, "
                    + "없는 frameId 는 404 FRAME_NOT_FOUND, CARD 에 frameId 는 400 INVALID_INPUT.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            encoding = @Encoding(name = "meta", contentType = MediaType.APPLICATION_JSON_VALUE)))
    @PostMapping(value = "/photocards", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<PhotocardResponse> create(
            @Parameter(description = "완성 이미지 파일 (multipart 필드명 image)", example = "photocard.png")
            @RequestPart("image") MultipartFile image,
            @Parameter(description = "메타 JSON 파트 (multipart 필드명 meta, Content-Type application/json)",
                    example = "{\"kind\":\"FOURCUT\",\"backgroundColor\":\"#1B2A4A\",\"frameId\":3,\"stickers\":[{\"stickerId\":\"code:star:HAPPY\",\"x\":540,\"y\":675,\"scale\":1,\"rotation\":0}],\"linkedRecordId\":31}")
            @RequestPart("meta") @Valid PhotocardCreateMeta meta,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        log.info("[포토카드 생성 요청] userId={}, kind={}, bytes={}", user.userId(), meta.kind(), image.getSize());
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID);
        }
        return ResponseDTO.okWithData(PhotocardResponse.from(createPhotocard.create(user.userId(), bytes, meta.toCommand())));
    }

    @Operation(summary = "포토카드 기록 연결 수정",
            description = "보낸 필드만 반영합니다. linkedRecordId 에 기록 ID 를 넣으면 그 기록으로 옮기고, null 이면 연결을 끊습니다. "
                    + "내 포토카드가 아니면 404 PHOTOCARD_NOT_FOUND, 내 기록이 아니면 404 RECORD_NOT_FOUND, 다른 카드가 붙은 기록이면 409 PHOTOCARD_ALREADY_LINKED. "
                    + "이미지와 메타(종류·배경색·프레임·스티커)는 합성 결과와 묶여 있어 바꿀 수 없습니다.")
    @PatchMapping("/photocards/{id}")
    public ResponseDTO<PhotocardResponse> update(
            @Parameter(description = "포토카드 ID", example = "15") @PathVariable Long id,
            @RequestBody PhotocardUpdateRequest request,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(PhotocardResponse.from(updatePhotocard.relink(user.userId(), id, request.linkedRecordId())));
    }

    @Operation(summary = "포토카드 삭제", description = "소프트 삭제하고 기록 연결을 끊습니다. 내 포토카드가 아니거나 이미 삭제됐으면 404 PHOTOCARD_NOT_FOUND.")
    @DeleteMapping("/photocards/{id}")
    public ResponseDTO<Void> delete(
            @Parameter(description = "포토카드 ID", example = "15") @PathVariable Long id,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        deletePhotocard.delete(user.userId(), id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "스티커 세트 목록 (공개)", description = "오늘(UTC) 배포 기간 안에 있는 세트만 냅니다. availableFrom/availableTo 가 null 이면 그 쪽은 제한 없음.")
    @GetMapping("/sticker-sets")
    public ResponseDTO<List<StickerSetResponse>> stickerSets() {
        return ResponseDTO.okWithData(stickerQuery.availableSets(LocalDate.now(ZoneOffset.UTC)).stream().map(StickerSetResponse::from).toList());
    }

    @Operation(summary = "네컷 프레임 목록 (공개)",
            description = "상시(ORIGINAL) 프레임 전부와 오늘(UTC)이 배포 기간 안인 스페셜(SPECIAL) 프레임을 냅니다. "
                    + "imageUrl 은 SVG, 확장자만 .json 으로 바꾼 같은 경로에 칸 좌표({canvas, slots})가 있습니다.")
    @GetMapping("/frames")
    public ResponseDTO<List<FrameResponse>> frames() {
        return ResponseDTO.okWithData(frameQuery.available(LocalDate.now(ZoneOffset.UTC)).stream().map(FrameResponse::from).toList());
    }
}
