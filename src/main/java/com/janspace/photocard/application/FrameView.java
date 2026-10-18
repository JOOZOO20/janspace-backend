package com.janspace.photocard.application;

import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.Frame;
import com.janspace.photocard.domain.FrameKind;
import com.janspace.photocard.domain.SlotRect;
import java.time.LocalDate;
import java.util.List;

/**
 * 프론트 FrameSet 계약과 같은 모양의 읽기 스냅샷. imageUrl 은 SVG 공개 URL 이고, 칸 좌표 JSON 은 확장자만 .json 인 같은 경로에 있다.
 */
public record FrameView(Long id, String name, FrameKind kind, String season, String imageUrl, List<SlotRect> slotRects,
                        LocalDate availableFrom, LocalDate availableTo) {

    static FrameView of(Frame frame, ImageStoragePort images) {
        return new FrameView(frame.getId(), frame.getName(), frame.getKind(), frame.getSeason(), images.publicUrl(frame.getImageKey()),
                frame.getSlotRects(), frame.getAvailableFrom(), frame.getAvailableUntil());
    }
}
