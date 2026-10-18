package com.janspace.photocard.application;

import com.janspace.photocard.application.port.ImageStoragePort;
import com.janspace.photocard.domain.Photocard;
import com.janspace.photocard.domain.PhotocardKind;
import com.janspace.photocard.domain.PlacedSticker;
import java.time.Instant;
import java.util.List;

/**
 * 프론트 Photocard 계약과 같은 모양의 읽기 스냅샷. imageUrl 은 저장 키를 CDN 공개 URL 로 바꾼 것.
 */
public record PhotocardView(Long id, PhotocardKind kind, String imageUrl, String backgroundColor, Long frameId,
                            List<PlacedSticker> stickers, Long linkedRecordId, Instant createdAt) {

    static PhotocardView of(Photocard card, ImageStoragePort images) {
        return new PhotocardView(card.getId(), card.getKind(), images.publicUrl(card.getImageKey()), card.getBackgroundColor(),
                card.getFrameId(), card.getStickers(), card.getAttendanceId(), card.getCreatedAt());
    }
}
