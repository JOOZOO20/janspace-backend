package com.janspace.photocard.application;

import com.janspace.photocard.domain.FrameKind;
import com.janspace.photocard.domain.SlotRect;
import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 프레임 upsert 입력(SVG 바이트 제외). id 가 없으면 신규라 SVG 와 slotRects 가 필요하고,
 * id 가 있으면 메타만 바꾸거나 SVG 와 slotRects 를 함께 교체한다.
 */
public record UpsertFrame(Long id, String name, FrameKind kind, String season, LocalDate availableFrom, LocalDate availableTo,
                          List<SlotRect> slotRects) {
}
