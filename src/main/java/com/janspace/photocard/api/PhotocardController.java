package com.janspace.photocard.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.common.auth.AuthenticatedUser;
import com.janspace.common.auth.CurrentUser;
import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.photocard.application.CreatePhotocardUseCase;
import com.janspace.photocard.application.DeletePhotocardUseCase;
import com.janspace.photocard.application.PhotocardQuery;
import com.janspace.photocard.application.StickerQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 포토카드 바인더(로그인 필수)와 공개 스티커 세트 목록. meta 파트는 브라우저 FormData 문자열 필드가 Content-Type 없이 오므로 @RequestParam 으로 받아 직접 파싱한다.
 */
@Slf4j
@Tag(name = "포토카드 (Photocard)", description = "포토카드 생성·바인더 조회·삭제, 스티커 세트 조회 API")
@RestController
@RequestMapping(V1_BASE)
@RequiredArgsConstructor
public class PhotocardController {

    private final CreatePhotocardUseCase createPhotocard;
    private final DeletePhotocardUseCase deletePhotocard;
    private final PhotocardQuery photocardQuery;
    private final StickerQuery stickerQuery;
    private final ObjectMapper objectMapper;

    @Operation(summary = "내 포토카드 목록", description = "내 바인더의 포토카드를 최신순으로 냅니다.")
    @GetMapping("/photocards")
    public ResponseDTO<List<PhotocardResponse>> list(@Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        return ResponseDTO.okWithData(photocardQuery.mine(user.userId()).stream().map(PhotocardResponse::from).toList());
    }

    @Operation(summary = "포토카드 생성",
            description = "multipart: image 파트(PNG/JPEG, 5MB 이하, 긴 변 1350px 이하 — 아니면 400 PHOTOCARD_IMAGE_INVALID) + meta 파트(JSON 문자열 "
                    + "{stickers: PlacedSticker[], linkedRecordId?: number|null}). 월 10장 초과는 429 PHOTOCARD_LIMIT, 내 기록이 아닌 linkedRecordId 는 404 RECORD_NOT_FOUND.")
    @PostMapping(value = "/photocards", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<PhotocardResponse> create(
            @Parameter(description = "이미지 파일 (multipart 필드명 image)", example = "photocard.png")
            @RequestPart("image") MultipartFile image,
            @Parameter(description = "메타 JSON 문자열 (multipart 필드명 meta)", example = "{\"stickers\":[{\"stickerId\":7,\"x\":0.4,\"y\":0.6,\"scale\":1,\"rotation\":0}],\"linkedRecordId\":31}")
            @RequestParam("meta") String meta,
            @Parameter(hidden = true) @CurrentUser AuthenticatedUser user) {
        log.info("[포토카드 생성 요청] userId={}, filename={}, bytes={}", user.userId(), image.getOriginalFilename(), image.getSize());
        PhotocardCreateMeta parsed = parse(meta, PhotocardCreateMeta.class);
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID);
        }
        return ResponseDTO.okWithData(PhotocardResponse.from(createPhotocard.create(user.userId(), bytes,
                parsed.stickersOrEmpty().stream().map(PlacedStickerDto::toDomain).toList(), parsed.linkedRecordId())));
    }

    @Operation(summary = "포토카드 삭제", description = "소프트 삭제합니다. 내 포토카드가 아니거나 이미 삭제됐으면 404 PHOTOCARD_NOT_FOUND.")
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

    private <T> T parse(String json, Class<T> type) {
        try {
            T parsed = objectMapper.readValue(json, type);
            if (parsed == null) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT, "meta 파트가 비어 있습니다.");
            }
            return parsed;
        } catch (JacksonException e) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "meta 파트가 올바른 JSON 이 아닙니다.");
        }
    }
}
