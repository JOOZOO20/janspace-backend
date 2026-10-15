package com.janspace.photocard.application;

import com.janspace.common.exception.ErrorCode;
import com.janspace.common.exception.predefined.BadRequestException;
import com.janspace.common.exception.predefined.NotFoundException;
import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.Frame;
import com.janspace.photocard.domain.FrameRepository;
import com.janspace.photocard.domain.SlotRect;
import com.janspace.photocard.domain.SvgValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 관리자 네컷 프레임 관리. SVG 는 frames/{uuid}.svg, 칸 좌표는 같은 이름의 frames/{uuid}.json 으로 둔다
 * (편집기가 SVG 주소의 확장자만 .json 으로 바꿔 칸 좌표를 읽는다). 파일을 바꿀 때마다 새 uuid 라 CDN 캐시가 옛 파일을 내지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminFrameCommand {

    private static final String SVG_TYPE = "image/svg+xml";
    private static final String JSON_TYPE = "application/json";

    private final FrameRepository frames;
    private final ImageStoragePort images;
    private final ObjectMapper objectMapper;

    @Transactional
    public FrameView upsert(UpsertFrame cmd, byte[] svg) {
        // 1. SVG 와 칸 좌표는 한 디자인(칸 = SVG 의 투명 구멍)이라 함께 오거나 함께 빠진다. 신규는 둘 다 필요하다
        boolean replacing = svg != null;
        if (replacing != (cmd.slotRects() != null)) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "frame 파일과 slotRects 는 함께 보내야 합니다.");
        }
        if (cmd.id() == null && !replacing) {
            throw new BadRequestException(ErrorCode.INVALID_INPUT, "새 프레임에는 frame 파일과 slotRects 가 필요합니다.");
        }

        // 2. 업로드 전에 파일·메타를 모두 검증한다 (틀리면 아무것도 올리지 않는다)
        if (replacing) {
            SvgValidator.validate(svg);
        }
        String base = replacing ? "frames/" + UUID.randomUUID() : null;
        String svgKey = replacing ? base + ".svg" : null;
        String name = cmd.name().trim();
        String season = cmd.season() == null || cmd.season().isBlank() ? null : cmd.season().trim();
        Frame frame;
        if (cmd.id() == null) {
            frame = new Frame(name, cmd.kind(), season, cmd.availableFrom(), cmd.availableTo(), svgKey, cmd.slotRects());
        } else {
            frame = frames.findByIdAndDeletedAtIsNull(cmd.id()).orElseThrow(() -> new NotFoundException(ErrorCode.FRAME_NOT_FOUND));
            frame.update(name, cmd.kind(), season, cmd.availableFrom(), cmd.availableTo());
            if (replacing) {
                frame.replaceFile(svgKey, cmd.slotRects());
            }
        }

        // 3. 외부 호출: 칸 좌표 JSON 과 SVG 업로드
        if (replacing) {
            images.put(base + ".json", layoutJson(cmd.slotRects()), JSON_TYPE);
            images.put(svgKey, svg, SVG_TYPE);
        }
        Frame saved = frames.save(frame);
        log.info("[프레임 upsert] frameId={}, 신규={}, kind={}, 파일 교체={}, key={}", saved.getId(), cmd.id() == null, saved.getKind(), replacing,
                saved.getImageKey());
        return FrameView.of(saved, images);
    }

    // 이미 만든 네컷의 frame_id 가 가리키므로 소프트 삭제한다. 공개·관리자 목록에서 빠진다
    @Transactional
    public void delete(Long frameId) {
        Frame frame = frames.findByIdAndDeletedAtIsNull(frameId).orElseThrow(() -> new NotFoundException(ErrorCode.FRAME_NOT_FOUND));
        frame.softDelete();
        log.info("[프레임 삭제] frameId={}", frameId);
    }

    // 프론트 프레임 생성 스크립트가 만드는 것과 같은 모양. 편집기는 slots 만 읽는다
    private byte[] layoutJson(List<SlotRect> slots) {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("canvas", List.of(Frame.CANVAS_WIDTH, Frame.CANVAS_HEIGHT));
        layout.put("slots", slots);
        return objectMapper.writeValueAsBytes(layout);
    }
}
