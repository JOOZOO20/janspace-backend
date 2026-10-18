package com.janspace.photocard.api;

import static com.janspace.common.constant.ApiVersion.V1_BASE;

import com.janspace.common.dto.ResponseDTO;
import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.photocard.application.AdminFrameCommand;
import com.janspace.photocard.application.FrameQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Encoding;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 관리자 네컷 프레임 관리. 관리자 권한 규칙은 보안 설정이 /admin/** 에 건다.
 */
@Slf4j
@Tag(name = "관리자 프레임 (Admin Frame)", description = "네컷 프레임 전체 목록, upsert(SVG·사진 칸 좌표·종류·배포 기간), 삭제")
@RestController
@RequestMapping(V1_BASE + "/admin/frames")
@RequiredArgsConstructor
public class AdminFrameController {

    private final AdminFrameCommand adminFrameCommand;
    private final FrameQuery frameQuery;

    @Operation(summary = "프레임 전체 목록 (관리자)", description = "배포 기간과 상관없이 삭제되지 않은 프레임을 전부 냅니다.")
    @GetMapping
    public ResponseDTO<List<FrameResponse>> list() {
        return ResponseDTO.okWithData(frameQuery.all().stream().map(FrameResponse::from).toList());
    }

    @Operation(summary = "프레임 upsert",
            description = "multipart: meta 파트(Content-Type application/json) + frame 파트(SVG, 2MB 이하, viewBox 1:3, script·foreignObject·이벤트 속성·javascript: 금지, "
                    + "아니면 400 FRAME_SVG_INVALID). 신규는 frame 파일과 meta.slotRects 가 필요하고, 갱신은 둘을 함께 보내 교체하거나 둘 다 빼고 메타만 바꿉니다. "
                    + "SVG 는 frames/{uuid}.svg, 칸 좌표는 같은 이름의 .json({canvas:[1080,3240], slots}) 으로 저장합니다. "
                    + "ORIGINAL 에 기간, SPECIAL 에 기간 누락은 400 INVALID_INPUT. 없는 id 는 404 FRAME_NOT_FOUND.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
            encoding = @Encoding(name = "meta", contentType = MediaType.APPLICATION_JSON_VALUE)))
    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseDTO<FrameResponse> upsert(
            @Parameter(description = "메타 JSON 파트 (multipart 필드명 meta, Content-Type application/json)",
                    example = "{\"name\":\"Sweat & Stardust\",\"kind\":\"SPECIAL\",\"season\":\"2026 아시아 투어\",\"availableFrom\":\"2026-10-10\",\"availableTo\":\"2026-12-19\",\"slotRects\":[{\"x\":90,\"y\":84,\"w\":900,\"h\":672},{\"x\":90,\"y\":804,\"w\":900,\"h\":672},{\"x\":90,\"y\":1524,\"w\":900,\"h\":672},{\"x\":90,\"y\":2244,\"w\":900,\"h\":672}]}")
            @RequestPart("meta") @Valid FrameUpsertMeta meta,
            @Parameter(description = "프레임 SVG 파일 (multipart 필드명 frame, 사진 칸은 투명)", example = "fr-stardust.svg")
            @RequestPart(value = "frame", required = false) MultipartFile frame) {
        byte[] svg = null;
        if (frame != null) {
            try {
                svg = frame.getBytes();
            } catch (IOException e) {
                throw new BadRequestException(ErrorCode.FRAME_SVG_INVALID);
            }
        }
        log.info("[관리자 프레임 upsert 요청] id={}, kind={}, file={}", meta.id(), meta.kind(), svg != null);
        return ResponseDTO.okWithData(FrameResponse.from(adminFrameCommand.upsert(meta.toCommand(), svg)));
    }

    @Operation(summary = "프레임 삭제",
            description = "소프트 삭제합니다. 이 프레임으로 만든 네컷은 그대로 남고(frameId 유지) 공개·관리자 목록에서만 빠집니다. 없거나 이미 삭제됐으면 404 FRAME_NOT_FOUND.")
    @DeleteMapping("/{id}")
    public ResponseDTO<Void> delete(@Parameter(description = "프레임 ID", example = "3") @PathVariable Long id) {
        adminFrameCommand.delete(id);
        return ResponseDTO.ok();
    }
}
