package com.janspace.photocard.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.photocard.application.AdminStickerCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자 스티커 세트 관리. 관리자 권한 규칙은 보안 설정이 /admin/** 에 건다.
 */
@Slf4j
@Tag(name = "관리자 스티커 (Admin Sticker)", description = "스티커 세트 upsert(시즌·배포 기간·번역·스티커 업로드), 스티커 삭제")
@RestController
@RequestMapping(V1_BASE + "/admin/sticker-sets")
@RequiredArgsConstructor
public class AdminStickerController {

    private final AdminStickerCommand adminStickerCommand;
    private final ObjectMapper objectMapper;

    @Operation(summary = "스티커 세트 upsert",
            description = "multipart: meta 파트(JSON 문자열 {id?, name: LocalizedText, originalLocale?, season?, availableFrom?, availableTo?, isDefault?}) "
                    + "+ stickers 파트(파일 0개 이상, PNG/JPEG 5MB 이하·긴 변 1350px 이하, 아니면 400 PHOTOCARD_IMAGE_INVALID). "
                    + "id 가 있으면 그 세트를 갱신하고 파일을 뒤에 추가합니다(404 STICKER_SET_NOT_FOUND). 번역은 전체 교체.")
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<StickerSetResponse> upsert(
            @Parameter(description = "메타 JSON 문자열 (multipart 필드명 meta)", example = "{\"name\":{\"original\":\"합창의 밤\",\"en\":\"Choir Night\"},\"season\":\"합창의 밤 2026\",\"availableFrom\":\"2026-11-01\",\"availableTo\":\"2026-12-31\"}")
            @RequestParam("meta") String meta,
            @Parameter(description = "스티커 이미지 파일들 (multipart 필드명 stickers, 업로드 순서 유지)", example = "sticker1.png")
            @RequestPart(value = "stickers", required = false) List<MultipartFile> stickers) {
        StickerSetUpsertMeta parsed = parse(meta);
        if (parsed.name() == null) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "name 은 필수입니다.");
        }
        List<byte[]> files = new ArrayList<>();
        for (MultipartFile file : stickers == null ? List.<MultipartFile>of() : stickers) {
            try {
                files.add(file.getBytes());
            } catch (IOException e) {
                throw new BadRequestException(ErrorCode.PHOTOCARD_IMAGE_INVALID);
            }
        }
        log.info("[관리자 스티커 세트 upsert 요청] id={}, name={}, files={}", parsed.id(), parsed.name().original(), files.size());
        return ResponseDTO.okWithData(StickerSetResponse.from(adminStickerCommand.upsert(parsed.toCommand(), files)));
    }

    @Operation(summary = "스티커 삭제", description = "세트에서 스티커 하나를 지웁니다. 세트가 없으면 404 STICKER_SET_NOT_FOUND, 스티커가 없으면 404 STICKER_NOT_FOUND.")
    @DeleteMapping("/{id}/stickers/{stickerId}")
    public ResponseDTO<Void> deleteSticker(
            @Parameter(description = "세트 ID", example = "2") @PathVariable Long id,
            @Parameter(description = "스티커 ID", example = "7") @PathVariable Long stickerId) {
        adminStickerCommand.deleteSticker(id, stickerId);
        return ResponseDTO.ok();
    }

    private StickerSetUpsertMeta parse(String json) {
        try {
            StickerSetUpsertMeta parsed = objectMapper.readValue(json, StickerSetUpsertMeta.class);
            if (parsed == null) {
                throw new BadRequestException(ErrorCode.INVALID_INPUT, "meta 파트가 비어 있습니다.");
            }
            return parsed;
        } catch (JacksonException e) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "meta 파트가 올바른 JSON 이 아닙니다.");
        }
    }
}
